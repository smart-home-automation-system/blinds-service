package cloud.cholewa.blinds.service;

import cloud.cholewa.blinds.config.EatonProperties;
import cloud.cholewa.blinds.driver.EatonDriver;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.state.BlindStateTracker;
import cloud.cholewa.blinds.state.BlindStateTracker.IssuedCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

//An Eaton actuator does not always get the 70 status of its movement through (HAS-291): on
//2026-10-16 the kitchen blind closed on a command of the dashboard and only its final stop arrived.
//So after an open or a close, when no movement was reported within confirm-after, the actuator is
//asked for its status once - the answer arrives as the usual status event, and the tracker reads
//it. A request that fails is a WARN and nothing more: the stop at the end of the run still tells
//the direction (BlindStateTracker.decide). When the window of the command passes with nothing at
//all, the command is unconfirmed - a WARN, the state left as it is.
//The gateway's acknowledgement of the command proves nothing here: on that day it took the
//telegram of the kitchen and the movement was never reported
@Slf4j
@Component
@RequiredArgsConstructor
public class EatonMoveConfirmation {

    private final BlindStateTracker blindStateTracker;
    private final EatonDriver eatonDriver;
    private final EatonProperties eatonProperties;

    /** Starts the wait for a command that was sent; nothing for one that waits for no movement. */
    public void start(final Blind blind, final IssuedCommand command) {
        if (!command.expectsMovement()) {
            return;
        }
        confirm(blind, command)
            .contextCapture()
            .subscribe(null, e -> log.error("The confirmation of blind {} ({}) ended with an error",
                blind.id(), blind.name(), e));
    }

    Mono<Void> confirm(final Blind blind, final IssuedCommand command) {
        return Mono.delay(eatonProperties.confirmAfter())
            .filter(tick -> blindStateTracker.awaitingMovement(blind, command))
            .flatMap(tick -> requestStatus(blind))
            .then(Mono.delay(BlindStateTracker.COMMAND_WINDOW.minus(eatonProperties.confirmAfter())))
            .filter(tick -> blindStateTracker.unconfirmed(blind, command))
            .doOnNext(tick -> log.warn("Blind {} ({}) reported nothing within {} of the command: unconfirmed, the state left as it is",
                blind.id(), blind.name(), BlindStateTracker.COMMAND_WINDOW))
            .then();
    }

    private Mono<Void> requestStatus(final Blind blind) {
        return eatonDriver.requestStatus(blind)
            .doOnSubscribe(subscription -> log.info("Blind {} ({}) reported no movement within {} of the command, its status is asked",
                blind.id(), blind.name(), eatonProperties.confirmAfter()))
            .onErrorResume(e -> {
                log.warn("The status of blind {} ({}) could not be asked: {}", blind.id(), blind.name(), e.toString());
                return Mono.empty();
            });
    }
}
