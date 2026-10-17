package cloud.cholewa.blinds.service;

import cloud.cholewa.blinds.driver.BlindsDriver;
import cloud.cholewa.blinds.driver.BlindsDrivers;
import cloud.cholewa.blinds.error.BlindsError;
import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.BlindCommand;
import cloud.cholewa.blinds.model.CommandSource;
import cloud.cholewa.blinds.model.Direction;
import cloud.cholewa.blinds.model.RoomCommandReply.BlindOutcome;
import cloud.cholewa.blinds.registry.BlindRegistry;
import cloud.cholewa.blinds.state.BlindStateTracker;
import cloud.cholewa.home.model.BlindsPlacement;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.shelly.http.ShellyCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlindsCommandServiceTest {

    private static final Blind KITCHEN = blind(17, RoomName.KITCHEN, false);
    private static final Blind OFFICE = blind(21, RoomName.OFFICE, true);
    private static final Blind OFFICE_DOOR = blind(22, RoomName.OFFICE, true);

    private final BlindRegistry registry = mock(BlindRegistry.class);
    private final BlindsDrivers drivers = mock(BlindsDrivers.class);
    private final BlindsDriver driver = mock(BlindsDriver.class);
    private final ProtectionGuard protection = mock(ProtectionGuard.class);
    private final BlindStateTracker tracker = mock(BlindStateTracker.class);
    private final EatonMoveConfirmation confirmation = mock(EatonMoveConfirmation.class);
    private final BlindsCommandService service = new BlindsCommandService(registry, drivers, protection, tracker, confirmation);

    @BeforeEach
    void setUp() {
        when(drivers.of(any())).thenReturn(driver);
        when(protection.check(any(), any())).thenReturn(Mono.empty());
        when(registry.find(17)).thenReturn(Mono.just(KITCHEN));
        when(registry.find(21)).thenReturn(Mono.just(OFFICE));
    }

    @Test
    void should_send_the_command_to_the_driver_of_the_blind() {
        when(driver.move(KITCHEN, BlindCommand.CLOSE)).thenReturn(Mono.empty());

        service.move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE).as(StepVerifier::create).verifyComplete();

        //noted before it goes out: the device may report the movement before it answers
        final InOrder order = inOrder(protection, tracker, driver);
        order.verify(protection).check(KITCHEN, CommandSource.SCHEDULE);
        order.verify(tracker).commanded(KITCHEN, CommandSource.SCHEDULE, false, Direction.CLOSE);
        order.verify(driver).move(KITCHEN, BlindCommand.CLOSE);
    }

    //HAS-291: the wait for the movement starts once the command is sent - with the command the
    //tracker noted, so a newer one is told apart; a stop and a position wait for no direction
    @Test
    void should_start_the_confirmation_of_a_sent_command_with_its_direction() {
        final BlindStateTracker.IssuedCommand issued = mock(BlindStateTracker.IssuedCommand.class);
        when(tracker.commanded(KITCHEN, CommandSource.DASHBOARD, false, Direction.OPEN)).thenReturn(issued);
        when(driver.move(KITCHEN, BlindCommand.OPEN)).thenReturn(Mono.empty());
        when(driver.move(KITCHEN, BlindCommand.STOP)).thenReturn(Mono.empty());
        when(driver.moveTo(OFFICE, 30)).thenReturn(Mono.empty());

        service.move(17, BlindCommand.OPEN, CommandSource.DASHBOARD).as(StepVerifier::create).verifyComplete();
        service.move(17, BlindCommand.STOP, CommandSource.DASHBOARD).as(StepVerifier::create).verifyComplete();
        service.moveTo(21, 30, CommandSource.DASHBOARD).as(StepVerifier::create).verifyComplete();

        final InOrder order = inOrder(driver, confirmation);
        order.verify(driver).move(KITCHEN, BlindCommand.OPEN);
        order.verify(confirmation).start(KITCHEN, issued);
        verify(tracker).commanded(KITCHEN, CommandSource.DASHBOARD, true, null);
        verify(tracker).commanded(OFFICE, CommandSource.DASHBOARD, false, null);
    }

    //a command that failed may have moved the blind, but nobody asks after it
    @Test
    void should_not_start_the_confirmation_of_a_command_that_failed() {
        when(driver.move(KITCHEN, BlindCommand.CLOSE)).thenReturn(Mono.error(new IllegalStateException("502")));

        service.move(17, BlindCommand.CLOSE, CommandSource.DASHBOARD).as(StepVerifier::create)
            .expectError(BlindsException.class)
            .verify();

        verify(confirmation, never()).start(any(), any());
    }

    @Test
    void should_send_a_blind_with_positioning_to_the_position() {
        when(driver.moveTo(OFFICE, 30)).thenReturn(Mono.empty());

        service.moveTo(21, 30, CommandSource.DASHBOARD).as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_refuse_a_position_for_a_blind_without_positioning() {
        service.moveTo(17, 30, CommandSource.DASHBOARD).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(((BlindsException) e).getError()).isEqualTo(BlindsError.UNSUPPORTED_POSITION))
            .verify();

        verify(driver, never()).moveTo(any(), anyInt());
    }

    //the protection decides before the device is called
    @Test
    void should_not_call_the_device_when_the_protection_refuses() {
        final AtomicBoolean called = new AtomicBoolean();
        when(protection.check(KITCHEN, CommandSource.DASHBOARD))
            .thenReturn(Mono.error(BlindsException.protectionActive(KITCHEN, "wind above the limit")));
        when(driver.move(KITCHEN, BlindCommand.OPEN)).thenReturn(Mono.fromRunnable(() -> called.set(true)));

        service.move(17, BlindCommand.OPEN, CommandSource.DASHBOARD).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(((BlindsException) e).getError()).isEqualTo(BlindsError.PROTECTION_ACTIVE))
            .verify();

        assertThat(called).isFalse();
        verify(tracker, never()).commanded(any(), any(), anyBoolean(), any());
    }

    @Test
    void should_answer_a_failed_device_as_device_failed_with_the_reason_for_the_log() {
        when(driver.move(KITCHEN, BlindCommand.OPEN))
            .thenReturn(Mono.error(new ShellyCallException("GET /roller/0 of 10.0.0.1 timed out")));

        service.move(17, BlindCommand.OPEN, CommandSource.DASHBOARD).as(StepVerifier::create)
            .expectErrorSatisfies(e -> {
                final BlindsException exception = (BlindsException) e;
                assertThat(exception.getError()).isEqualTo(BlindsError.DEVICE_FAILED);
                assertThat(exception.getHttpStatus().value()).isEqualTo(502);
                assertThat(exception.getMessage()).contains("blind 17", "timed out");
            })
            .verify();
    }

    @Test
    void should_pass_an_unknown_blind_on() {
        when(registry.find(99)).thenReturn(Mono.error(BlindsException.notFound("no blind with id 99")));

        service.move(99, BlindCommand.OPEN, CommandSource.DASHBOARD).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(((BlindsException) e).getError()).isEqualTo(BlindsError.NOT_FOUND_BLINDS))
            .verify();
    }

    //one after the other; the failed one is named and the rest still move
    @Test
    void should_move_every_blind_of_the_room_and_name_the_ones_that_failed() {
        when(registry.inRoom(RoomName.OFFICE)).thenReturn(Mono.just(List.of(OFFICE, OFFICE_DOOR, KITCHEN)));
        when(driver.move(OFFICE, BlindCommand.STOP)).thenReturn(Mono.error(new ShellyCallException("refused")));
        when(driver.move(OFFICE_DOOR, BlindCommand.STOP)).thenReturn(Mono.empty());
        when(driver.move(KITCHEN, BlindCommand.STOP)).thenReturn(Mono.empty());

        service.moveRoom(RoomName.OFFICE, BlindCommand.STOP, CommandSource.DASHBOARD).as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.room()).isEqualTo("office");
                assertThat(reply.command()).isEqualTo("stop");
                assertThat(reply.moved()).containsExactly(
                    new BlindOutcome(22, "Blind 22", null), new BlindOutcome(17, "Blind 17", null));
                assertThat(reply.failed()).containsExactly(new BlindOutcome(21, "Blind 21", "DEVICE_FAILED"));
            })
            .verifyComplete();

        final InOrder order = inOrder(driver);
        order.verify(driver).move(OFFICE, BlindCommand.STOP);
        order.verify(driver).move(OFFICE_DOOR, BlindCommand.STOP);
        order.verify(driver).move(KITCHEN, BlindCommand.STOP);
    }

    @Test
    void should_name_a_blind_the_protection_kept_with_its_code() {
        when(registry.inRoom(RoomName.OFFICE)).thenReturn(Mono.just(List.of(OFFICE, OFFICE_DOOR)));
        when(protection.check(OFFICE, CommandSource.SCHEDULE))
            .thenReturn(Mono.error(BlindsException.protectionActive(OFFICE, "frost")));
        when(driver.move(OFFICE_DOOR, BlindCommand.OPEN)).thenReturn(Mono.empty());

        service.moveRoom(RoomName.OFFICE, BlindCommand.OPEN, CommandSource.SCHEDULE).as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.moved()).containsExactly(new BlindOutcome(22, "Blind 22", null));
                assertThat(reply.failed()).containsExactly(new BlindOutcome(21, "Blind 21", "PROTECTION_ACTIVE"));
            })
            .verifyComplete();
    }

    //nothing to accept when nothing moved: a failed device wins over a refusal
    @Test
    void should_fail_the_room_as_device_failed_when_nothing_moved_and_a_device_failed() {
        when(registry.inRoom(RoomName.OFFICE)).thenReturn(Mono.just(List.of(OFFICE, OFFICE_DOOR)));
        when(protection.check(OFFICE, CommandSource.DASHBOARD))
            .thenReturn(Mono.error(BlindsException.protectionActive(OFFICE, "frost")));
        when(driver.move(OFFICE_DOOR, BlindCommand.OPEN)).thenReturn(Mono.error(new ShellyCallException("timed out")));

        service.moveRoom(RoomName.OFFICE, BlindCommand.OPEN, CommandSource.DASHBOARD).as(StepVerifier::create)
            .expectErrorSatisfies(e -> {
                final BlindsException exception = (BlindsException) e;
                assertThat(exception.getError()).isEqualTo(BlindsError.DEVICE_FAILED);
                assertThat(exception.getHttpStatus().value()).isEqualTo(502);
            })
            .verify();
    }

    @Test
    void should_fail_the_room_with_the_refusal_when_the_protection_kept_every_blind() {
        when(registry.inRoom(RoomName.OFFICE)).thenReturn(Mono.just(List.of(OFFICE)));
        when(protection.check(OFFICE, CommandSource.SCHEDULE))
            .thenReturn(Mono.error(BlindsException.protectionActive(OFFICE, "frost")));

        service.moveRoom(RoomName.OFFICE, BlindCommand.OPEN, CommandSource.SCHEDULE).as(StepVerifier::create)
            .expectErrorSatisfies(e -> {
                final BlindsException exception = (BlindsException) e;
                assertThat(exception.getError()).isEqualTo(BlindsError.PROTECTION_ACTIVE);
                assertThat(exception.getHttpStatus().value()).isEqualTo(409);
            })
            .verify();
    }

    @Test
    void should_pass_a_room_without_blinds_on() {
        when(registry.inRoom(RoomName.SAUNA)).thenReturn(Mono.error(BlindsException.notFound("no blind in the room sauna")));

        service.moveRoom(RoomName.SAUNA, BlindCommand.OPEN, CommandSource.DASHBOARD).as(StepVerifier::create)
            .expectError(BlindsException.class)
            .verify();
    }

    private static Blind blind(final long id, final RoomName room, final boolean positioning) {
        return new Blind(id, "Blind " + id, room, SmartVendor.SHELLY, new BlindBinding.ShellyGen1("10.0.0.1", 0),
            BlindsPlacement.INTERNAL, positioning);
    }
}
