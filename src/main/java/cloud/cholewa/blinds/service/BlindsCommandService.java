package cloud.cholewa.blinds.service;

import cloud.cholewa.blinds.driver.BlindsDriver;
import cloud.cholewa.blinds.driver.BlindsDrivers;
import cloud.cholewa.blinds.error.BlindsError;
import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindCommand;
import cloud.cholewa.blinds.model.CommandSource;
import cloud.cholewa.blinds.model.RoomCommandReply;
import cloud.cholewa.blinds.model.RoomCommandReply.BlindOutcome;
import cloud.cholewa.blinds.registry.BlindRegistry;
import cloud.cholewa.commons.error.model.ErrorId;
import cloud.cholewa.home.model.RoomName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;

//The commands for the blinds. Every one is checked by the protection first, logged with who asked
//for it, and sent once - nothing is retried, a move that may have happened is not repeated. A
//command writes no state: where a blind is comes only from what the devices report (HAS-245).
//Whatever a driver fails with becomes DEVICE_FAILED, a 502, with the reason in the log
@Slf4j
@Service
@RequiredArgsConstructor
public class BlindsCommandService {

    private final BlindRegistry blindRegistry;
    private final BlindsDrivers blindsDrivers;
    private final ProtectionGuard protectionGuard;

    public Mono<Void> move(final long id, final BlindCommand command, final CommandSource source) {
        return blindRegistry.find(id).flatMap(blind -> move(blind, command, source));
    }

    public Mono<Void> moveTo(final long id, final int position, final CommandSource source) {
        return blindRegistry.find(id).flatMap(blind -> blind.positioning()
            ? execute(blind, source, "go to " + position, driver -> driver.moveTo(blind, position))
            : Mono.error(BlindsException.unsupportedPosition(blind)));
    }

    //one blind after the other, in the order of the registry: the actuators of a room share a
    //gateway or a circuit. One that fails is skipped and named - the others still move
    public Mono<RoomCommandReply> moveRoom(final RoomName room, final BlindCommand command, final CommandSource source) {
        return blindRegistry.inRoom(room)
            .flatMapMany(Flux::fromIterable)
            .concatMap(blind -> move(blind, command, source)
                .thenReturn(new Outcome(blind, null))
                .onErrorResume(e -> {
                    final BlindsError error = e instanceof BlindsException blindsException
                        ? blindsException.getError()
                        : BlindsError.DEVICE_FAILED;
                    log.error("Blind {} of the room {} skipped: {}", blind.id(), room.getValue(), e.getMessage());
                    return Mono.just(new Outcome(blind, error));
                }))
            .collectList()
            .map(outcomes -> new RoomCommandReply(
                room.getValue(),
                command.value(),
                outcomes.stream().filter(outcome -> outcome.error() == null).map(Outcome::toReply).toList(),
                outcomes.stream().filter(outcome -> outcome.error() != null).map(Outcome::toReply).toList()
            ));
    }

    private Mono<Void> move(final Blind blind, final BlindCommand command, final CommandSource source) {
        return execute(blind, source, command.value(), driver -> driver.move(blind, command));
    }

    private Mono<Void> execute(
        final Blind blind,
        final CommandSource source,
        final String what,
        final Function<BlindsDriver, Mono<Void>> call
    ) {
        return protectionGuard.check(blind, source)
            .then(Mono.defer(() -> {
                log.info("Blind {} ({}, {}) asked to {} by {}",
                    blind.id(), blind.name(), blind.room().getValue(), what, source.value());
                return call.apply(blindsDrivers.of(blind));
            }))
            .onErrorMap(e -> !(e instanceof BlindsException), e -> BlindsException.deviceFailed(blind, e));
    }

    private record Outcome(Blind blind, BlindsError error) {

        private BlindOutcome toReply() {
            return new BlindOutcome(blind.id(), blind.name(), error == null ? null : ErrorId.codeOf(error));
        }
    }
}
