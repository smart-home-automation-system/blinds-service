package cloud.cholewa.blinds.service;

import cloud.cholewa.blinds.config.EatonProperties;
import cloud.cholewa.blinds.driver.EatonDriver;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.CommandTarget;
import cloud.cholewa.blinds.state.BlindStateTracker;
import cloud.cholewa.blinds.state.BlindStateTracker.IssuedCommand;
import cloud.cholewa.blinds.state.BlindStateTracker.Unconfirmed;
import cloud.cholewa.commons.notification.NotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

//Every command that was sent waits for a report of the device that answers it (HAS-250): a Gen2+
//cover publishes its change at once, so it gets a minute; a Gen1 roller is polled every minute and an
//Eaton actuator may need a status request, so they get two (BlindStateTracker.COMMAND_WINDOW). A
//command nothing answered is a WARN, lastCommandConfirmed false in the reads, the state left as it is;
//the third in a row is an alert - once, until a command of the blind is confirmed or it moves. A blind
//that does not answer at all is left to the availability monitor, which tells of it by itself.
//An Eaton actuator does not always get the 70 status of its movement through (HAS-291): on
//2026-10-16 the kitchen blind closed on a command of the dashboard and only its final stop arrived.
//So after an open or a close, when no movement was reported within confirm-after, the actuator is
//asked for its status once - the answer arrives as the usual status event, and the tracker reads
//it. A request that fails is a WARN and nothing more: the stop at the end of the run still tells
//the direction (BlindStateTracker.decide). The gateway's acknowledgement of the command proves
//nothing here: on that day it took the telegram of the kitchen and the movement was never reported
@Slf4j
@Component
@RequiredArgsConstructor
public class MoveConfirmation {

    static final int ALERT_IN_A_ROW = 3;
    //a notification neither fails nor stalls what it reports on
    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(30);

    private final BlindStateTracker blindStateTracker;
    private final EatonDriver eatonDriver;
    private final EatonProperties eatonProperties;
    private final NotificationPublisher notificationPublisher;

    /** Starts the wait for a command that was sent; nothing for one that waits for nothing. */
    public void start(final Blind blind, final IssuedCommand command) {
        if (!command.awaitsConfirmation()) {
            return;
        }
        confirm(blind, command)
            .contextCapture()
            .subscribe(null, e -> log.error("The confirmation of blind {} ({}) ended with an error",
                blind.id(), blind.name(), e));
    }

    Mono<Void> confirm(final Blind blind, final IssuedCommand command) {
        final boolean askEaton = command.expectsMovement();
        final Mono<Void> ask = askEaton
            ? Mono.delay(eatonProperties.confirmAfter())
                .filter(tick -> blindStateTracker.awaitingMovement(blind, command))
                .flatMap(tick -> requestStatus(blind))
            : Mono.empty();
        final Duration rest = askEaton ? command.window().minus(eatonProperties.confirmAfter()) : command.window();

        return ask
            .then(Mono.delay(rest))
            .map(tick -> blindStateTracker.unconfirmed(blind, command))
            .filter(Unconfirmed::reported)
            .flatMap(unconfirmed -> {
                log.warn("Blind {} ({}) reported nothing within {} of the command: unconfirmed ({} in a row), the state left as it is",
                    blind.id(), blind.name(), command.window(), unconfirmed.inARow());
                return unconfirmed.inARow() >= ALERT_IN_A_ROW && !unconfirmed.announced()
                    ? alert(blind, command, unconfirmed.inARow())
                    : Mono.empty();
            });
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

    //not announced when the alert fails: the next unconfirmed command tries again
    private Mono<Void> alert(final Blind blind, final IssuedCommand command, final int inARow) {
        return blindStateTracker.status(blind.id()).flatMap(status -> {
            if (Boolean.FALSE.equals(status.reachable())) {
                log.info("Blind {} ({}) left {} commands in a row unconfirmed and does not answer: the availability monitor tells of it",
                    blind.id(), blind.name(), inARow);
                return Mono.empty();
            }
            final String text = alertText(blind, command, inARow);
            return notificationPublisher.publishAlert(text)
                .timeout(PUBLISH_TIMEOUT)
                .doOnSuccess(sent -> {
                    blindStateTracker.unconfirmedAnnounced(blind);
                    log.info("Unconfirmed commands notification published: {}", text);
                })
                .onErrorResume(e -> {
                    log.error("Unconfirmed commands notification not published, the next unconfirmed command tries again: {}",
                        e.toString());
                    return Mono.empty();
                });
        });
    }

    static String alertText(final Blind blind, final IssuedCommand command, final int inARow) {
        return "The blind " + blind.name() + " (" + blind.room().getValue() + ", " + blind.id() + ") has not confirmed "
            + inARow + " commands in a row: the last one, " + describe(command.target()) + " by " + command.source()
            + ", reported nothing within " + command.window().toMinutes() + " min.";
    }

    private static String describe(final CommandTarget target) {
        if (target.stop()) {
            return "stop";
        }
        if (target.direction() != null) {
            return target.direction().value();
        }
        return "go to " + target.position();
    }
}
