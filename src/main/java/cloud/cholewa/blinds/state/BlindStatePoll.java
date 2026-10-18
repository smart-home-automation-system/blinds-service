package cloud.cholewa.blinds.state;

import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.BlindReading;
import cloud.cholewa.blinds.registry.BlindRegistry;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.model.RollerStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

//The states the devices are asked for. A Gen1 roller is not on MQTT (it would lose the Shelly
//cloud), so /status of every one is read every minute - its position, and what the actions
//(roller_*_url) did not tell. A Gen2+ cover reports on MQTT by itself; Cover.GetStatus every five
//minutes reconciles what a lost message or a restart of the pod left behind.
//Each device is asked with its own error handling, so one that does not answer neither stops the
//pass nor delays the others for long. A Gen1 roller that does not answer becomes unreachable - the
//poll is all that is heard of it. A Gen2+ cover does not: its reachability is what MQTT says
//(online, and every status it publishes), and one failed HTTP call next to a live MQTT stream would
//only make it flap. Nothing is retried - the next pass asks again. fixedDelay: the next pass waits
//for the last one to end; each pass is built anew (Mono.defer), Spring subscribes again to the same
//Mono for every run. Off under the test profile
@Slf4j
@Component
@RequiredArgsConstructor
public class BlindStatePoll {

    //a few devices at once: a pass of devices that all time out still ends well within the minute
    private static final int CONCURRENCY = 4;

    private final BlindRegistry blindRegistry;
    private final BlindStateTracker blindStateTracker;
    private final ShellyGen1Client shellyGen1Client;
    private final ShellyRpcClient shellyRpcClient;

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "PT60S")
    Mono<Void> pollGen1() {
        return Mono.defer(() -> pass(blind -> blind.binding() instanceof BlindBinding.ShellyGen1, this::readGen1,
            (blind, e) -> blindStateTracker.reachability(blind, false, e.toString())));
    }

    @Scheduled(initialDelayString = "PT40S", fixedDelayString = "PT5M")
    Mono<Void> reconcileGen2() {
        return Mono.defer(() -> pass(blind -> blind.binding() instanceof BlindBinding.ShellyGen2, this::readGen2,
            (blind, e) -> Mono.fromRunnable(() -> log.info("Reconciliation of blind {} ({}) failed: {}",
                blind.id(), blind.name(), e.toString()))));
    }

    private Mono<Void> pass(
        final Predicate<Blind> kind,
        final Function<Blind, Mono<Void>> read,
        final BiFunction<Blind, Throwable, Mono<Void>> failed
    ) {
        final List<Blind> blinds = blindRegistry.current().stream().filter(kind).toList();

        return Flux.fromIterable(blinds)
            .flatMap(blind -> read.apply(blind)
                .onErrorResume(e -> {
                    log.debug("Blind {} ({}) did not answer the status read: {}", blind.id(), blind.name(), e.toString());
                    return failed.apply(blind, e).onErrorResume(ignored -> Mono.empty());
                }), CONCURRENCY)
            .then();
    }

    //the reading is applied apart from the read: a failed write of the state is not a device that
    //did not answer. Also the read right after a Gen1 roller reported a stop (BlindsQueryService)
    public Mono<Void> readGen1(final Blind blind) {
        final BlindBinding.ShellyGen1 roller = (BlindBinding.ShellyGen1) blind.binding();

        return shellyGen1Client.status(roller.address())
            .flatMap(status -> {
                final List<RollerStatus> rollers = status.getRollers();
                if (rollers == null || roller.channel() >= rollers.size() || rollers.get(roller.channel()) == null) {
                    return Mono.error(new IllegalStateException("no roller " + roller.channel() + " in the status"));
                }
                return Mono.just(rollers.get(roller.channel()));
            })
            .flatMap(status -> apply(blind, BlindReadings.ofRoller(status).orElse(null), status.getState()));
    }

    Mono<Void> readGen2(final Blind blind) {
        final BlindBinding.ShellyGen2 cover = (BlindBinding.ShellyGen2) blind.binding();

        return shellyRpcClient.coverStatus(cover.address(), cover.channel())
            .flatMap(status -> apply(blind, BlindReadings.ofCover(status).orElse(null), status.getState()));
    }

    private Mono<Void> apply(final Blind blind, final BlindReading reading, final String state) {
        if (reading == null) {
            log.debug("Blind {} ({}) reported the state {}, which is not read", blind.id(), blind.name(), state);
            return blindStateTracker.reachability(blind, true, null);
        }
        return blindStateTracker.observe(blind, reading)
            .onErrorResume(e -> {
                log.error("The state of blind {} ({}) could not be stored", blind.id(), blind.name(), e);
                return Mono.empty();
            });
    }
}
