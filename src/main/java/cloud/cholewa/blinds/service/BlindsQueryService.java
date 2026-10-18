package cloud.cholewa.blinds.service;

import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.BlindView;
import cloud.cholewa.blinds.model.RoomBlinds;
import cloud.cholewa.blinds.registry.BlindRegistry;
import cloud.cholewa.blinds.state.BlindReadings;
import cloud.cholewa.blinds.state.BlindStatePoll;
import cloud.cholewa.blinds.state.BlindStateTracker;
import cloud.cholewa.blinds.state.Gen1Action;
import cloud.cholewa.blinds.service.schedule.SchedulePauseService;
import cloud.cholewa.blinds.window.WindowSensors;
import cloud.cholewa.home.model.RoomName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

//The reads of the blinds - the registry's blinds with the state the devices reported - and the Gen1
//actions, which are reports of a device that arrive over HTTP
@Slf4j
@Service
@RequiredArgsConstructor
public class BlindsQueryService {

    private final BlindRegistry blindRegistry;
    private final BlindStateTracker blindStateTracker;
    private final WindowSensors windowSensors;
    private final SchedulePauseService schedulePauseService;
    private final BlindStatePoll blindStatePoll;

    //every blind, by room in the order of the rooms of the SDK, each room's in the order of the registry
    public Mono<List<RoomBlinds>> all() {
        return Mono.zip(blindRegistry.all(), schedulePauseService.pausedAsLastKnown())
            .flatMap(read -> Flux.fromIterable(read.getT1()).concatMap(blind -> view(blind, read.getT2())).collectList())
            .map(views -> {
                final Map<String, List<BlindView>> byRoom = views.stream()
                    .collect(Collectors.groupingBy(BlindView::room, LinkedHashMap::new, Collectors.toList()));
                return Arrays.stream(RoomName.values())
                    .map(RoomName::getValue)
                    .filter(byRoom::containsKey)
                    .map(room -> new RoomBlinds(room, byRoom.get(room)))
                    .toList();
            });
    }

    public Mono<RoomBlinds> inRoom(final RoomName room) {
        return Mono.zip(blindRegistry.inRoom(room), schedulePauseService.pausedAsLastKnown())
            .flatMap(read -> Flux.fromIterable(read.getT1()).concatMap(blind -> view(blind, read.getT2())).collectList())
            .map(views -> new RoomBlinds(room.getValue(), views));
    }

    public Mono<BlindView> one(final long id) {
        return Mono.zip(blindRegistry.find(id), schedulePauseService.pausedAsLastKnown())
            .flatMap(read -> view(read.getT1(), read.getT2()));
    }

    //idempotent: the same action twice is the same state, confirmed. A device that is not a Gen1
    //blind of the registry is a 404 - a device configured with the wrong id, or one removed
    public Mono<Void> gen1Action(final long device, final Gen1Action action) {
        return blindRegistry.find(device)
            .filter(blind -> blind.binding() instanceof BlindBinding.ShellyGen1)
            .switchIfEmpty(Mono.error(() -> BlindsException.notFound("no Gen1 blind with id " + device)))
            .flatMap(blind -> {
                log.debug("Gen1 action {} of blind {} ({})", action, blind.id(), blind.name());
                //a stop: where the roller stopped - an end or anywhere - is read from it at once,
                //instead of a minute later by the poll; a device that does not answer leaves the
                //stop the action told
                if (action != Gen1Action.STOP) {
                    return observe(blind, action);
                }
                return blindStatePoll.readGen1(blind).onErrorResume(e -> {
                    log.warn("Blind {} ({}) reported a stop and did not answer the status read: {}",
                        blind.id(), blind.name(), e.toString());
                    return observe(blind, action);
                });
            });
    }

    //the state is known once it is in memory; a store that failed is this service's trouble, not
    //the device's, and a device does not call again
    private Mono<Void> observe(final Blind blind, final Gen1Action action) {
        return blindStateTracker.observe(blind, BlindReadings.ofGen1Action(action))
            .onErrorResume(e -> {
                log.error("The state of blind {} ({}) could not be stored", blind.id(), blind.name(), e);
                return Mono.empty();
            });
    }

    private Mono<BlindView> view(final Blind blind, final Map<Long, LocalDateTime> paused) {
        return blindStateTracker.status(blind.id())
            .map(status -> BlindView.of(blind, status, windowSensors.stateOf(blind), paused.get(blind.id())));
    }
}
