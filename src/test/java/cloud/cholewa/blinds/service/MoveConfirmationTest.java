package cloud.cholewa.blinds.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.blinds.config.EatonProperties;
import cloud.cholewa.blinds.driver.EatonDriver;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.BlindStatus;
import cloud.cholewa.blinds.model.CommandTarget;
import cloud.cholewa.blinds.model.Direction;
import cloud.cholewa.blinds.state.BlindStateTracker;
import cloud.cholewa.blinds.state.BlindStateTracker.IssuedCommand;
import cloud.cholewa.blinds.state.BlindStateTracker.Unconfirmed;
import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.home.model.BlindsPlacement;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MoveConfirmationTest {

    private static final Duration CONFIRM_AFTER = Duration.ofSeconds(5);
    private static final Duration GEN2_WINDOW = Duration.ofSeconds(60);
    private static final Blind KITCHEN = new Blind(9, "Kitchen", RoomName.KITCHEN, SmartVendor.EATON,
        new BlindBinding.Eaton(9), BlindsPlacement.EXTERNAL, false);
    private static final Blind DOOR = new Blind(53, "Office door", RoomName.OFFICE, SmartVendor.SHELLY,
        new BlindBinding.ShellyGen2("10.0.0.50", 0, "shellyplus2pm-a1"), BlindsPlacement.EXTERNAL, true);

    private final BlindStateTracker tracker = mock(BlindStateTracker.class);
    private final EatonDriver driver = mock(EatonDriver.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final IssuedCommand eatonClose = mock(IssuedCommand.class);
    private final IssuedCommand coverOpen = mock(IssuedCommand.class);
    private final MoveConfirmation confirmation =
        new MoveConfirmation(tracker, driver, new EatonProperties(CONFIRM_AFTER), publisher);
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();

    @BeforeEach
    void setUp() {
        log.start();
        ((Logger) LoggerFactory.getLogger(MoveConfirmation.class)).addAppender(log);
        when(driver.requestStatus(KITCHEN)).thenReturn(Mono.empty());
        when(tracker.unconfirmed(any(), any())).thenReturn(Unconfirmed.NONE);
        when(tracker.status(53)).thenReturn(Mono.just(BlindStatus.unknown().withReachable(true)));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());

        when(eatonClose.awaitsConfirmation()).thenReturn(true);
        when(eatonClose.expectsMovement()).thenReturn(true);
        when(eatonClose.window()).thenReturn(BlindStateTracker.COMMAND_WINDOW);
        when(eatonClose.target()).thenReturn(CommandTarget.move(Direction.CLOSE));
        when(eatonClose.source()).thenReturn("dashboard");

        when(coverOpen.awaitsConfirmation()).thenReturn(true);
        when(coverOpen.expectsMovement()).thenReturn(false);
        when(coverOpen.window()).thenReturn(GEN2_WINDOW);
        when(coverOpen.target()).thenReturn(CommandTarget.move(Direction.OPEN));
        when(coverOpen.source()).thenReturn("schedule");
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(MoveConfirmation.class)).detachAppender(log);
    }

    // ---- the status request of an Eaton actuator (HAS-291)

    @Test
    void should_ask_for_the_status_once_when_no_movement_was_reported_after_confirm_after() {
        when(tracker.awaitingMovement(KITCHEN, eatonClose)).thenReturn(true);

        StepVerifier.withVirtualTime(() -> confirmation.confirm(KITCHEN, eatonClose))
            .thenAwait(CONFIRM_AFTER.minusMillis(1))
            .then(() -> verify(driver, never()).requestStatus(any()))
            .thenAwait(Duration.ofMillis(1))
            .then(() -> verify(driver).requestStatus(KITCHEN))
            .thenAwait(BlindStateTracker.COMMAND_WINDOW)
            .verifyComplete();
    }

    @Test
    void should_not_ask_when_the_movement_was_reported() {
        when(tracker.awaitingMovement(KITCHEN, eatonClose)).thenReturn(false);

        StepVerifier.withVirtualTime(() -> confirmation.confirm(KITCHEN, eatonClose))
            .thenAwait(BlindStateTracker.COMMAND_WINDOW)
            .verifyComplete();

        verify(driver, never()).requestStatus(any());
        assertThat(log.list).filteredOn(event -> event.getLevel() == Level.WARN).isEmpty();
    }

    //the stop at the end of the run still tells the direction: a failed request is a WARN and the wait goes on
    @Test
    void should_go_on_waiting_after_a_status_request_that_failed() {
        when(tracker.awaitingMovement(KITCHEN, eatonClose)).thenReturn(true);
        when(driver.requestStatus(KITCHEN)).thenReturn(Mono.error(new IllegalStateException("amx-service answered 502")));

        StepVerifier.withVirtualTime(() -> confirmation.confirm(KITCHEN, eatonClose))
            .thenAwait(BlindStateTracker.COMMAND_WINDOW)
            .verifyComplete();

        verify(tracker).unconfirmed(KITCHEN, eatonClose);
        assertThat(log.list).filteredOn(event -> event.getLevel() == Level.WARN)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("The status of blind 9 (Kitchen) could not be asked: java.lang.IllegalStateException: amx-service answered 502");
    }

    // ---- the answer to every command (HAS-250)

    //nothing within the window of the command: a WARN, and the tracker leaves the state as it is
    @Test
    void should_warn_about_an_eaton_command_nothing_confirmed_when_its_window_has_passed() {
        when(tracker.awaitingMovement(KITCHEN, eatonClose)).thenReturn(true);
        when(tracker.unconfirmed(KITCHEN, eatonClose)).thenReturn(new Unconfirmed(1, false));

        StepVerifier.withVirtualTime(() -> confirmation.confirm(KITCHEN, eatonClose))
            .thenAwait(BlindStateTracker.COMMAND_WINDOW.minusMillis(1))
            .then(() -> verify(tracker, never()).unconfirmed(any(), any()))
            .thenAwait(Duration.ofMillis(1))
            .verifyComplete();

        assertThat(log.list).filteredOn(event -> event.getLevel() == Level.WARN)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("Blind 9 (Kitchen) reported nothing within PT2M of the command: unconfirmed (1 in a row), the state left as it is");
    }

    //a Shelly reports by itself: no request, the minute of a Gen2+ cover
    @Test
    void should_judge_a_shelly_command_at_the_end_of_its_own_window_without_asking() {
        when(tracker.unconfirmed(DOOR, coverOpen)).thenReturn(new Unconfirmed(1, false));

        StepVerifier.withVirtualTime(() -> confirmation.confirm(DOOR, coverOpen))
            .thenAwait(GEN2_WINDOW.minusMillis(1))
            .then(() -> verify(tracker, never()).unconfirmed(any(), any()))
            .thenAwait(Duration.ofMillis(1))
            .verifyComplete();

        verify(tracker, never()).awaitingMovement(any(), any());
        verify(driver, never()).requestStatus(any());
        verify(publisher, never()).publishAlert(anyString());
        assertThat(log.list).filteredOn(event -> event.getLevel() == Level.WARN)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("Blind 53 (Office door) reported nothing within PT1M of the command: unconfirmed (1 in a row), the state left as it is");
    }

    @Test
    void should_alert_at_the_third_unconfirmed_command_in_a_row() {
        when(tracker.unconfirmed(DOOR, coverOpen)).thenReturn(new Unconfirmed(3, false));

        StepVerifier.withVirtualTime(() -> confirmation.confirm(DOOR, coverOpen))
            .thenAwait(GEN2_WINDOW)
            .verifyComplete();

        verify(publisher).publishAlert("The blind Office door (office, 53) has not confirmed 3 commands in a row: "
            + "the last one, open by schedule, reported nothing within 1 min.");
        verify(tracker).unconfirmedAnnounced(DOOR);
    }

    @Test
    void should_not_alert_twice_in_one_run_nor_before_the_third() {
        when(tracker.unconfirmed(DOOR, coverOpen)).thenReturn(new Unconfirmed(2, false), new Unconfirmed(4, true));

        StepVerifier.withVirtualTime(() -> confirmation.confirm(DOOR, coverOpen)).thenAwait(GEN2_WINDOW).verifyComplete();
        StepVerifier.withVirtualTime(() -> confirmation.confirm(DOOR, coverOpen)).thenAwait(GEN2_WINDOW).verifyComplete();

        verify(publisher, never()).publishAlert(anyString());
    }

    //a blind that does not answer at all is the availability monitor's to tell of
    @Test
    void should_leave_a_blind_that_does_not_answer_to_the_availability_monitor() {
        when(tracker.unconfirmed(DOOR, coverOpen)).thenReturn(new Unconfirmed(3, false));
        when(tracker.status(53)).thenReturn(Mono.just(BlindStatus.unknown().withReachable(false)));

        StepVerifier.withVirtualTime(() -> confirmation.confirm(DOOR, coverOpen)).thenAwait(GEN2_WINDOW).verifyComplete();

        verify(publisher, never()).publishAlert(anyString());
        verify(tracker, never()).unconfirmedAnnounced(any());
    }

    //the next unconfirmed command tries again; the wait itself ends well
    @Test
    void should_not_mark_an_alert_that_was_not_published_as_announced() {
        when(tracker.unconfirmed(DOOR, coverOpen)).thenReturn(new Unconfirmed(3, false));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.error(new IllegalStateException("broker away")));

        StepVerifier.withVirtualTime(() -> confirmation.confirm(DOOR, coverOpen)).thenAwait(GEN2_WINDOW).verifyComplete();

        verify(tracker, never()).unconfirmedAnnounced(any());
        assertThat(log.list).filteredOn(event -> event.getLevel() == Level.ERROR)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("Unconfirmed commands notification not published, the next unconfirmed command tries again: "
                + "java.lang.IllegalStateException: broker away");
    }

    //a blind already where the command asked: nothing to wait for, nothing scheduled
    @Test
    void should_start_nothing_for_a_command_that_waits_for_nothing() {
        when(coverOpen.awaitsConfirmation()).thenReturn(false);

        confirmation.start(DOOR, coverOpen);

        verify(tracker, never()).awaitingMovement(any(), any());
        verify(tracker, never()).unconfirmed(any(), any());
    }

    @Test
    void should_name_every_kind_of_command_in_the_alert() {
        when(coverOpen.target()).thenReturn(CommandTarget.position(30));
        assertThat(MoveConfirmation.alertText(DOOR, coverOpen, 3)).contains("the last one, go to 30 by schedule");
        when(coverOpen.target()).thenReturn(CommandTarget.halt());
        assertThat(MoveConfirmation.alertText(DOOR, coverOpen, 3)).contains("the last one, stop by schedule");
    }
}
