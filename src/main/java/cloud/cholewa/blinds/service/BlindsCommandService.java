package cloud.cholewa.blinds.service;

import cloud.cholewa.blinds.driver.BlindsDriver;
import cloud.cholewa.blinds.driver.BlindsDrivers;
import cloud.cholewa.blinds.error.BlindsError;
import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindCommand;
import cloud.cholewa.blinds.model.CommandSource;
import cloud.cholewa.blinds.model.Direction;
import cloud.cholewa.blinds.model.RoomCommandReply;
import cloud.cholewa.blinds.model.RoomCommandReply.BlindOutcome;
import cloud.cholewa.blinds.registry.BlindRegistry;
import cloud.cholewa.blinds.state.BlindStateTracker;
import cloud.cholewa.blinds.state.BlindStateTracker.IssuedCommand;
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
//command writes no state: where a blind is comes only from what the devices report
//(BlindStateTracker), which attributes the movement the command starts to its source.
//Whatever a driver fails with becomes DEVICE_FAILED, a 502, with the reason in the log
@Slf4j
@Service
@RequiredArgsConstructor
public class BlindsCommandService {

    private final BlindRegistry blindRegistry;
    private final BlindsDrivers blindsDrivers;
    private final ProtectionGuard protectionGuard;
    private final BlindStateTracker blindStateTracker;
    private final EatonMoveConfirmation eatonMoveConfirmation;

    public Mono<Void> move(final long id, final BlindCommand command, final CommandSource source) {
        return blindRegistry.find(id).flatMap(blind -> move(blind, command, source));
    }

    public Mono<Void> moveTo(final long id, final int position, final CommandSource source) {
        return blindRegistry.find(id).flatMap(blind -> blind.positioning()
            ? execute(blind, source, "go to " + position, false, null, driver -> driver.moveTo(blind, position))
            : Mono.error(BlindsException.unsupportedPosition(blind)));
    }

    //One blind after the other, in the order of the registry: the actuators of a room share a
    //gateway or a circuit. One that fails is skipped and named - the others still move. When none
    //moved there is nothing to accept: the room fails like a single blind would - DEVICE_FAILED
    //when a device failed, otherwise the refusal of the first one
    public Mono<RoomCommandReply> moveRoom(final RoomName room, final BlindCommand command, final CommandSource source) {
        return blindRegistry.inRoom(room)
            .flatMapMany(Flux::fromIterable)
            .concatMap(blind -> move(blind, command, source)
                .thenReturn(new Outcome(blind, null))
                //execute() leaves nothing but a BlindsException
                .onErrorResume(BlindsException.class, e -> {
                    if (e.getHttpStatus().is5xxServerError()) {
                        log.error("Blind {} of the room {} skipped: {}", blind.id(), room.getValue(), e.getMessage());
                    } else {
                        log.warn("Blind {} of the room {} skipped: {}", blind.id(), room.getValue(), e.getMessage());
                    }
                    return Mono.just(new Outcome(blind, e));
                }))
            .collectList()
            .flatMap(outcomes -> outcomes.stream().anyMatch(outcome -> outcome.failure() == null)
                ? Mono.just(new RoomCommandReply(
                    room.getValue(),
                    command.value(),
                    outcomes.stream().filter(outcome -> outcome.failure() == null).map(Outcome::toReply).toList(),
                    outcomes.stream().filter(outcome -> outcome.failure() != null).map(Outcome::toReply).toList()))
                : Mono.error(nothingMoved(room, outcomes)));
    }

    private static BlindsException nothingMoved(final RoomName room, final List<Outcome> outcomes) {
        final BlindsException first = outcomes.stream()
            .map(Outcome::failure)
            .filter(failure -> failure.getError() == BlindsError.DEVICE_FAILED)
            .findFirst()
            .orElse(outcomes.getFirst().failure());

        return new BlindsException(first.getHttpStatus(), first.getError(),
            "no blind of the room " + room.getValue() + " moved");
    }

    private Mono<Void> move(final Blind blind, final BlindCommand command, final CommandSource source) {
        return execute(blind, source, command.value(), command == BlindCommand.STOP, directionOf(command),
            driver -> driver.move(blind, command));
    }

    private Mono<Void> execute(
        final Blind blind,
        final CommandSource source,
        final String what,
        final boolean stop,
        final Direction direction,
        final Function<BlindsDriver, Mono<Void>> call
    ) {
        return protectionGuard.check(blind, source)
            .then(Mono.defer(() -> {
                log.info("Blind {} ({}, {}) asked to {} by {}",
                    blind.id(), blind.name(), blind.room().getValue(), what, source.value());
                final IssuedCommand issued = blindStateTracker.commanded(blind, source, stop, direction);
                //the wait for the movement starts once the command is sent; a command that failed
                //may have moved the blind, and its movement is still attributed, but nobody asks
                return call.apply(blindsDrivers.of(blind))
                    .doOnSuccess(sent -> eatonMoveConfirmation.start(blind, issued));
            }))
            .onErrorMap(e -> !(e instanceof BlindsException), e -> BlindsException.deviceFailed(blind, e));
    }

    private static Direction directionOf(final BlindCommand command) {
        return switch (command) {
            case OPEN -> Direction.OPEN;
            case CLOSE -> Direction.CLOSE;
            case STOP -> null;
        };
    }

    private record Outcome(Blind blind, BlindsException failure) {

        private BlindOutcome toReply() {
            return new BlindOutcome(blind.id(), blind.name(), failure == null ? null : ErrorId.codeOf(failure.getError()));
        }
    }
}
