package cloud.cholewa.blinds.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.blinds.config.EatonProperties;
import cloud.cholewa.blinds.driver.EatonDriver;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.state.BlindStateTracker;
import cloud.cholewa.blinds.state.BlindStateTracker.IssuedCommand;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EatonMoveConfirmationTest {

    private static final Duration CONFIRM_AFTER = Duration.ofSeconds(5);
    private static final Blind KITCHEN = new Blind(9, "Kitchen", RoomName.KITCHEN, SmartVendor.EATON,
        new BlindBinding.Eaton(9), BlindsPlacement.EXTERNAL, false);

    private final BlindStateTracker tracker = mock(BlindStateTracker.class);
    private final EatonDriver driver = mock(EatonDriver.class);
    private final IssuedCommand issued = mock(IssuedCommand.class);
    private final EatonMoveConfirmation confirmation =
        new EatonMoveConfirmation(tracker, driver, new EatonProperties(CONFIRM_AFTER));
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();

    @BeforeEach
    void setUp() {
        log.start();
        ((Logger) LoggerFactory.getLogger(EatonMoveConfirmation.class)).addAppender(log);
        when(driver.requestStatus(KITCHEN)).thenReturn(Mono.empty());
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(EatonMoveConfirmation.class)).detachAppender(log);
    }

    @Test
    void should_ask_for_the_status_once_when_no_movement_was_reported_after_confirm_after() {
        when(tracker.awaitingMovement(KITCHEN, issued)).thenReturn(true);

        StepVerifier.withVirtualTime(() -> confirmation.confirm(KITCHEN, issued))
            .thenAwait(CONFIRM_AFTER.minusMillis(1))
            .then(() -> verify(driver, never()).requestStatus(any()))
            .thenAwait(Duration.ofMillis(1))
            .then(() -> verify(driver).requestStatus(KITCHEN))
            .thenAwait(BlindStateTracker.COMMAND_WINDOW)
            .verifyComplete();
    }

    @Test
    void should_not_ask_when_the_movement_was_reported() {
        when(tracker.awaitingMovement(KITCHEN, issued)).thenReturn(false);

        StepVerifier.withVirtualTime(() -> confirmation.confirm(KITCHEN, issued))
            .thenAwait(BlindStateTracker.COMMAND_WINDOW)
            .verifyComplete();

        verify(driver, never()).requestStatus(any());
        assertThat(levels()).doesNotContain(Level.WARN);
    }

    //nothing within the window of the command: a WARN, and the tracker leaves the state as it is
    @Test
    void should_warn_about_a_command_nothing_confirmed_when_its_window_has_passed() {
        when(tracker.awaitingMovement(KITCHEN, issued)).thenReturn(true);
        when(tracker.unconfirmed(KITCHEN, issued)).thenReturn(true);

        StepVerifier.withVirtualTime(() -> confirmation.confirm(KITCHEN, issued))
            .thenAwait(BlindStateTracker.COMMAND_WINDOW.minusMillis(1))
            .then(() -> verify(tracker, never()).unconfirmed(any(), any()))
            .thenAwait(Duration.ofMillis(1))
            .verifyComplete();

        verify(tracker).unconfirmed(KITCHEN, issued);
        assertThat(log.list).filteredOn(event -> event.getLevel() == Level.WARN)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("Blind 9 (Kitchen) reported nothing within PT2M of the command: unconfirmed, the state left as it is");
    }

    //the stop at the end of the run still tells the direction: a failed request is a WARN and the wait goes on
    @Test
    void should_go_on_waiting_after_a_status_request_that_failed() {
        when(tracker.awaitingMovement(KITCHEN, issued)).thenReturn(true);
        when(driver.requestStatus(KITCHEN)).thenReturn(Mono.error(new IllegalStateException("amx-service answered 502")));

        StepVerifier.withVirtualTime(() -> confirmation.confirm(KITCHEN, issued))
            .thenAwait(BlindStateTracker.COMMAND_WINDOW)
            .verifyComplete();

        verify(tracker).unconfirmed(KITCHEN, issued);
        assertThat(log.list).filteredOn(event -> event.getLevel() == Level.WARN)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("The status of blind 9 (Kitchen) could not be asked: java.lang.IllegalStateException: amx-service answered 502");
    }

    //a stop, a position, a Shelly: nothing to wait for, nothing scheduled
    @Test
    void should_start_nothing_for_a_command_that_waits_for_no_movement() {
        when(issued.expectsMovement()).thenReturn(false);

        confirmation.start(KITCHEN, issued);

        verify(tracker, never()).awaitingMovement(any(), any());
    }

    private java.util.List<Level> levels() {
        return log.list.stream().map(ILoggingEvent::getLevel).toList();
    }
}
