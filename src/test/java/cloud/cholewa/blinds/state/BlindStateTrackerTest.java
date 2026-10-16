package cloud.cholewa.blinds.state;

import cloud.cholewa.blinds.database.model.BlindStateEntity;
import cloud.cholewa.blinds.database.repository.BlindStateRepository;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.BlindReading;
import cloud.cholewa.blinds.model.BlindState;
import cloud.cholewa.blinds.model.BlindStatus;
import cloud.cholewa.blinds.model.CommandSource;
import cloud.cholewa.blinds.model.Direction;
import cloud.cholewa.blinds.state.BlindStateTracker.IssuedCommand;
import cloud.cholewa.home.model.BlindsPlacement;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlindStateTrackerTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final Instant START = Instant.parse("2026-10-15T16:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(START, HOUSE);

    private static final Blind DOOR = new Blind(53, "Office door", RoomName.OFFICE, SmartVendor.SHELLY,
        new BlindBinding.ShellyGen2("10.0.0.50", 0, "shellyplus2pm-a1"), BlindsPlacement.EXTERNAL, true);
    private static final Blind KITCHEN = new Blind(9, "Kitchen", RoomName.KITCHEN, SmartVendor.EATON,
        new BlindBinding.Eaton(9), BlindsPlacement.EXTERNAL, false);

    private final MovableClock clock = new MovableClock(START);
    private final BlindStateRepository repository = mock(BlindStateRepository.class);
    private BlindStateTracker tracker;

    @BeforeEach
    void setUp() {
        when(repository.findLatestPerDevice()).thenReturn(Flux.empty());
        when(repository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(repository.touchLatest(anyLong(), any(), any())).thenReturn(Mono.just(1));
        tracker = new BlindStateTracker(repository, clock);
    }

    // ---- the merge of a reading into what is known

    @Test
    void should_take_a_new_state_as_a_change_with_its_position_and_time() {
        final BlindStateTracker.Decision decision = BlindStateTracker.decide(
            known(BlindState.CLOSED, 0, Direction.CLOSE), reading(BlindState.OPENING, 10, "SHC"), NOW, null);

        assertThat(decision.changed()).isTrue();
        assertThat(decision.status()).isEqualTo(
            new BlindStatus(BlindState.OPENING, 10, Direction.OPEN, NOW, "SHC", true));
    }

    @Test
    void should_confirm_the_same_state_and_keep_when_it_began() {
        final BlindStatus before = known(BlindState.OPEN, 100, Direction.OPEN);

        final BlindStateTracker.Decision decision =
            BlindStateTracker.decide(before, reading(BlindState.OPEN, 100, "init"), NOW, null);

        assertThat(decision.changed()).isFalse();
        assertThat(decision.status()).isEqualTo(new BlindStatus(
            BlindState.OPEN, 100, Direction.OPEN, before.movedAt(), before.source(), true));
    }

    //the position of a moving blind is kept up to date without a row per percent
    @Test
    void should_follow_the_position_of_a_moving_blind_without_a_change() {
        final BlindStateTracker.Decision decision = BlindStateTracker.decide(
            known(BlindState.CLOSING, 80, Direction.CLOSE), reading(BlindState.CLOSING, 40, null), NOW, null);

        assertThat(decision.changed()).isFalse();
        assertThat(decision.status().position()).isEqualTo(40);
    }

    //a blind at rest with another position moved between two readings
    @Test
    void should_take_another_position_at_rest_as_a_change() {
        final BlindStateTracker.Decision decision = BlindStateTracker.decide(
            known(BlindState.STOPPED, 40, Direction.OPEN), reading(BlindState.STOPPED, 60, null), NOW, null);

        assertThat(decision.changed()).isTrue();
        assertThat(decision.status().position()).isEqualTo(60);
        assertThat(decision.status().source()).isEqualTo("external");
    }

    //a reading without a position does not say the blind lost it
    @Test
    void should_keep_the_position_a_reading_does_not_have() {
        final BlindStateTracker.Decision decision = BlindStateTracker.decide(
            known(BlindState.STOPPED, 40, Direction.OPEN), reading(BlindState.STOPPED, null, null), NOW, null);

        assertThat(decision.changed()).isFalse();
        assertThat(decision.status().position()).isEqualTo(40);
    }

    //a Gen1 roller without calibration reports "stop" at an end it reported reaching a moment ago
    @Test
    void should_take_a_rest_without_a_position_as_the_end_the_blind_reached() {
        final BlindStateTracker.Decision decision = BlindStateTracker.decide(known(BlindState.OPEN, null, Direction.OPEN),
            new BlindReading(BlindState.STOPPED, null, Direction.OPEN, "http"), NOW, null);

        assertThat(decision.changed()).isFalse();
        assertThat(decision.status().state()).isEqualTo(BlindState.OPEN);
    }

    //the roller_stop_url action: stopped anywhere, the direction is not in it
    @Test
    void should_take_a_stop_action_after_an_end_as_a_stop() {
        final BlindStateTracker.Decision decision = BlindStateTracker.decide(known(BlindState.OPEN, null, Direction.OPEN),
            new BlindReading(BlindState.STOPPED, null, null, null), NOW, null);

        assertThat(decision.changed()).isTrue();
        assertThat(decision.status().state()).isEqualTo(BlindState.STOPPED);
    }

    //closed halfway between two polls: the rest is not the open end any more
    @Test
    void should_take_a_rest_that_moved_away_from_the_end_as_a_stop() {
        final BlindStateTracker.Decision decision = BlindStateTracker.decide(known(BlindState.OPEN, null, Direction.OPEN),
            new BlindReading(BlindState.STOPPED, null, Direction.CLOSE, "http"), NOW, null);

        assertThat(decision.changed()).isTrue();
        assertThat(decision.status().state()).isEqualTo(BlindState.STOPPED);
        assertThat(decision.status().lastDirection()).isEqualTo(Direction.CLOSE);
    }

    //a wall button closes, the dashboard opens: the stop of the button's movement is the button's,
    //and the command waits for the movement it starts
    @Test
    void should_leave_a_command_other_than_stop_for_the_movement_it_starts() {
        final BlindStatus closing = new BlindStatus(BlindState.CLOSING, null, Direction.CLOSE, NOW.minusSeconds(5), "external", true);

        final BlindStateTracker.Decision decision = BlindStateTracker.decide(closing,
            new BlindReading(BlindState.STOPPED, null, null, null), NOW, new BlindStateTracker.PendingCommand("dashboard", false));

        assertThat(decision.status().source()).isEqualTo("external");
        assertThat(decision.commandUsed()).isFalse();
    }

    @Test
    void should_give_the_rest_a_stop_command_ended_to_the_command() {
        final BlindStatus closing = new BlindStatus(BlindState.CLOSING, null, Direction.CLOSE, NOW.minusSeconds(5), "external", true);

        final BlindStateTracker.Decision decision = BlindStateTracker.decide(closing,
            new BlindReading(BlindState.STOPPED, null, null, null), NOW, new BlindStateTracker.PendingCommand("schedule", true));

        assertThat(decision.status().source()).isEqualTo("schedule");
        assertThat(decision.commandUsed()).isTrue();
    }

    @Test
    void should_give_the_movement_of_a_command_the_source_of_the_command() {
        final BlindStateTracker.Decision decision = BlindStateTracker.decide(
            known(BlindState.CLOSED, 0, Direction.CLOSE), reading(BlindState.OPENING, 5, "HTTP_in"), NOW,
            new BlindStateTracker.PendingCommand("schedule", false));

        assertThat(decision.status().source()).isEqualTo("schedule");
        assertThat(decision.commandUsed()).isTrue();
    }

    //the end of a movement belongs to whoever started it, not to "limit_switch"
    @Test
    void should_give_the_rest_after_a_movement_the_source_of_the_movement() {
        final BlindStatus moving = new BlindStatus(BlindState.OPENING, 50, Direction.OPEN, NOW.minusSeconds(20), "dashboard", true);

        final BlindStateTracker.Decision decision =
            BlindStateTracker.decide(moving, reading(BlindState.OPEN, 100, "limit_switch"), NOW, null);

        assertThat(decision.changed()).isTrue();
        assertThat(decision.status().source()).isEqualTo("dashboard");
    }

    @Test
    void should_name_a_change_nobody_reported_external() {
        final BlindStateTracker.Decision decision =
            BlindStateTracker.decide(BlindStatus.unknown(), reading(BlindState.CLOSING, null, null), NOW, null);

        assertThat(decision.status().source()).isEqualTo("external");
        assertThat(decision.status().lastDirection()).isEqualTo(Direction.CLOSE);
    }

    @Test
    void should_cut_a_long_word_of_a_device_to_the_column() {
        final BlindStateTracker.Decision decision = BlindStateTracker.decide(
            BlindStatus.unknown(), reading(BlindState.STOPPED, null, "x".repeat(80)), NOW, null);

        assertThat(decision.status().source()).hasSize(50);
    }

    @Test
    void should_keep_the_last_direction_through_a_rest() {
        final BlindStateTracker.Decision decision = BlindStateTracker.decide(
            known(BlindState.CLOSING, null, Direction.CLOSE), reading(BlindState.STOPPED, null, null), NOW, null);

        assertThat(decision.status().lastDirection()).isEqualTo(Direction.CLOSE);
    }

    // ---- what is stored

    @Test
    void should_insert_a_row_for_a_change_and_touch_the_latest_for_a_confirmation() {
        tracker.observe(DOOR, reading(BlindState.CLOSED, 0, "init")).as(StepVerifier::create).verifyComplete();
        clock.move(Duration.ofMinutes(1));
        tracker.observe(DOOR, reading(BlindState.CLOSED, 0, "init")).as(StepVerifier::create).verifyComplete();

        final ArgumentCaptor<BlindStateEntity> saved = ArgumentCaptor.forClass(BlindStateEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue()).isEqualTo(new BlindStateEntity(
            null, 53, BlindState.CLOSED, 0, null, "init", NOW, NOW));
        verify(repository).touchLatest(53, NOW.plusMinutes(1), 0);
    }

    //the column keeps microseconds: the time in memory must be the one a restart reads back
    @Test
    void should_keep_the_time_of_a_change_to_the_microsecond() {
        clock.move(Duration.ofNanos(1_234_567));

        tracker.observe(DOOR, reading(BlindState.CLOSED, 0, null)).as(StepVerifier::create).verifyComplete();

        tracker.status(53).map(BlindStatus::movedAt).as(StepVerifier::create)
            .expectNext(NOW.plusNanos(1_234_000))
            .verifyComplete();
    }

    //rows removed by hand: the state is stored again rather than confirmed into nothing
    @Test
    void should_insert_a_row_when_there_is_none_to_confirm() {
        when(repository.touchLatest(anyLong(), any(), any())).thenReturn(Mono.just(0));
        tracker.observe(DOOR, reading(BlindState.CLOSED, 0, null)).as(StepVerifier::create).verifyComplete();
        tracker.observe(DOOR, reading(BlindState.CLOSED, 0, null)).as(StepVerifier::create).verifyComplete();

        verify(repository, times(2)).save(any());
    }

    @Test
    void should_restore_the_latest_rows_once_before_the_first_reading() {
        final AtomicInteger reads = new AtomicInteger();
        when(repository.findLatestPerDevice()).thenReturn(Flux.defer(() -> {
            reads.incrementAndGet();
            return Flux.just(new BlindStateEntity(7L, 53, BlindState.OPEN, 100, Direction.OPEN, "dashboard",
                NOW.minusHours(3), NOW.minusMinutes(2)));
        }));
        tracker = new BlindStateTracker(repository, clock);

        tracker.status(53).as(StepVerifier::create)
            .expectNext(new BlindStatus(BlindState.OPEN, 100, Direction.OPEN, NOW.minusHours(3), "dashboard", null))
            .verifyComplete();
        //the same state from the device confirms what was restored: no new row
        tracker.observe(DOOR, reading(BlindState.OPEN, 100, "init")).as(StepVerifier::create).verifyComplete();

        assertThat(reads).hasValue(1);
        verify(repository, never()).save(any());
        verify(repository).touchLatest(53, NOW, 100);
    }

    @Test
    void should_start_from_nothing_when_the_database_cannot_be_read() {
        when(repository.findLatestPerDevice()).thenReturn(Flux.error(new IllegalStateException("no connection")));
        tracker = new BlindStateTracker(repository, clock);

        tracker.status(53).as(StepVerifier::create).expectNext(BlindStatus.unknown()).verifyComplete();
        tracker.observe(DOOR, reading(BlindState.CLOSED, 0, null)).as(StepVerifier::create).verifyComplete();
    }

    //the memory moved on: the error is the caller's to log, the state is known all the same
    @Test
    void should_pass_a_failed_write_on_and_keep_the_state() {
        doReturn(Mono.error(new IllegalStateException("no connection"))).when(repository).save(any());

        tracker.observe(DOOR, reading(BlindState.CLOSING, null, null)).as(StepVerifier::create)
            .expectError(IllegalStateException.class)
            .verify();
        tracker.status(53).map(BlindStatus::state).as(StepVerifier::create).expectNext(BlindState.CLOSING).verifyComplete();
    }

    //the latest row is what a restart reads back: a change must not overtake the one before it
    @Test
    void should_write_the_rows_of_a_blind_in_the_order_of_the_changes() {
        final Sinks.Empty<Void> firstStored = Sinks.empty();
        final List<BlindState> stored = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            final BlindStateEntity row = invocation.getArgument(0);
            stored.add(row.state());
            return row.state() == BlindState.CLOSING ? firstStored.asMono().thenReturn(row) : Mono.just(row);
        }).when(repository).save(any());

        tracker.observe(DOOR, reading(BlindState.CLOSING, null, null)).subscribe();
        tracker.observe(DOOR, reading(BlindState.CLOSED, 0, null)).as(StepVerifier::create)
            .expectSubscription()
            .then(() -> {
                assertThat(stored).containsExactly(BlindState.CLOSING);
                firstStored.tryEmitEmpty();
            })
            .verifyComplete();

        assertThat(stored).containsExactly(BlindState.CLOSING, BlindState.CLOSED);
    }

    @Test
    void should_not_hold_the_next_write_up_after_one_that_failed() {
        doReturn(Mono.error(new IllegalStateException("no connection")), Mono.just(entityOf(BlindState.CLOSED)))
            .when(repository).save(any());

        tracker.observe(DOOR, reading(BlindState.CLOSING, null, null)).as(StepVerifier::create)
            .expectError(IllegalStateException.class)
            .verify();
        tracker.observe(DOOR, reading(BlindState.CLOSED, 0, null)).as(StepVerifier::create).verifyComplete();
    }

    // ---- who moved it

    @Test
    void should_attribute_the_next_change_to_a_command_once() {
        tracker.commanded(DOOR, CommandSource.DASHBOARD, false, Direction.OPEN);
        clock.move(Duration.ofSeconds(2));
        tracker.observe(DOOR, reading(BlindState.OPENING, 10, "HTTP_in")).as(StepVerifier::create).verifyComplete();
        tracker.observe(DOOR, reading(BlindState.OPEN, 100, "limit_switch")).as(StepVerifier::create).verifyComplete();
        tracker.observe(DOOR, reading(BlindState.CLOSING, 90, "SHC")).as(StepVerifier::create).verifyComplete();

        final ArgumentCaptor<BlindStateEntity> saved = ArgumentCaptor.forClass(BlindStateEntity.class);
        verify(repository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(BlindStateEntity::source)
            .containsExactly("dashboard", "dashboard", "SHC");
    }

    @Test
    void should_not_attribute_a_change_long_after_the_command() {
        tracker.commanded(DOOR, CommandSource.SCHEDULE, false, Direction.CLOSE);
        clock.move(BlindStateTracker.COMMAND_WINDOW.plusSeconds(1));
        tracker.observe(DOOR, reading(BlindState.CLOSING, 90, "button")).as(StepVerifier::create).verifyComplete();

        tracker.status(53).map(BlindStatus::source).as(StepVerifier::create).expectNext("button").verifyComplete();
    }

    // ---- an Eaton movement whose start was never reported (HAS-291)

    //the kitchen of 2026-10-16: closed on a command, only the final 00 arrived, and the blind
    //still showed the direction of the movement before
    @Test
    void should_take_a_lone_stop_after_a_close_as_closed_by_the_command() {
        final BlindStatus openedBefore = known(BlindState.STOPPED, null, Direction.OPEN);

        final BlindStateTracker.Decision decision = BlindStateTracker.decide(openedBefore,
            new BlindReading(BlindState.STOPPED, null, null, null), NOW,
            new BlindStateTracker.PendingCommand("dashboard", false, Direction.CLOSE));

        assertThat(decision.changed()).isTrue();
        assertThat(decision.commandUsed()).isTrue();
        assertThat(decision.status()).isEqualTo(
            new BlindStatus(BlindState.STOPPED, null, Direction.CLOSE, NOW, "dashboard", true));
    }

    //the answer to the status request: the movement, with the source of the command
    @Test
    void should_take_a_status_answer_02_as_closing_by_the_command() {
        final BlindStateTracker.Decision decision = BlindStateTracker.decide(
            known(BlindState.STOPPED, null, Direction.OPEN), BlindReadings.ofEatonStatus(2).orElseThrow(), NOW,
            new BlindStateTracker.PendingCommand("dashboard", false, Direction.CLOSE));

        assertThat(decision.commandUsed()).isTrue();
        assertThat(decision.status()).isEqualTo(
            new BlindStatus(BlindState.CLOSING, null, Direction.CLOSE, NOW, "dashboard", true));
    }

    //a stop command keeps its own meaning: the rest it ends says nothing of a direction
    @Test
    void should_not_read_a_direction_into_the_rest_after_a_stop_command() {
        final BlindStateTracker.Decision decision = BlindStateTracker.decide(
            known(BlindState.STOPPED, null, Direction.OPEN), new BlindReading(BlindState.STOPPED, null, null, null), NOW,
            new BlindStateTracker.PendingCommand("dashboard", true, null));

        assertThat(decision.status().lastDirection()).isEqualTo(Direction.OPEN);
    }

    @Test
    void should_end_the_movement_of_an_eaton_command_with_a_lone_stop_and_attribute_it_once() {
        final IssuedCommand issued = tracker.commanded(KITCHEN, CommandSource.DASHBOARD, false, Direction.CLOSE);
        clock.move(Duration.ofSeconds(30));
        tracker.observe(KITCHEN, BlindReadings.ofEatonStatus(0).orElseThrow()).as(StepVerifier::create).verifyComplete();

        tracker.status(9).as(StepVerifier::create)
            .expectNext(new BlindStatus(BlindState.STOPPED, null, Direction.CLOSE, NOW.plusSeconds(30), "dashboard", true))
            .verifyComplete();
        assertThat(tracker.awaitingMovement(KITCHEN, issued)).isFalse();
        assertThat(tracker.unconfirmed(KITCHEN, issued)).isFalse();
    }

    //nothing at all within the window: unconfirmed, and the state is what it was
    @Test
    void should_leave_the_state_of_a_command_nothing_confirmed() {
        tracker.observe(KITCHEN, new BlindReading(BlindState.STOPPED, null, Direction.OPEN, null))
            .as(StepVerifier::create).verifyComplete();
        final IssuedCommand issued = tracker.commanded(KITCHEN, CommandSource.SCHEDULE, false, Direction.CLOSE);

        assertThat(tracker.awaitingMovement(KITCHEN, issued)).isTrue();
        clock.move(BlindStateTracker.COMMAND_WINDOW);
        assertThat(tracker.unconfirmed(KITCHEN, issued)).isTrue();

        tracker.status(9).map(BlindStatus::lastDirection).as(StepVerifier::create).expectNext(Direction.OPEN).verifyComplete();
        assertThat(tracker.unconfirmed(KITCHEN, issued)).isFalse();
    }

    @Test
    void should_stop_waiting_once_the_movement_is_reported() {
        final IssuedCommand issued = tracker.commanded(KITCHEN, CommandSource.DASHBOARD, false, Direction.OPEN);
        tracker.observe(KITCHEN, BlindReadings.ofEatonStatus(1).orElseThrow()).as(StepVerifier::create).verifyComplete();

        assertThat(tracker.awaitingMovement(KITCHEN, issued)).isFalse();
        assertThat(tracker.unconfirmed(KITCHEN, issued)).isFalse();
    }

    //a movement the state already had: nothing changes, and still the command has its answer
    @Test
    void should_take_a_movement_the_state_already_had_as_the_answer() {
        tracker.observe(KITCHEN, BlindReadings.ofEatonStatus(2).orElseThrow()).as(StepVerifier::create).verifyComplete();
        final IssuedCommand issued = tracker.commanded(KITCHEN, CommandSource.DASHBOARD, false, Direction.CLOSE);
        tracker.observe(KITCHEN, BlindReadings.ofEatonStatus(2).orElseThrow()).as(StepVerifier::create).verifyComplete();

        assertThat(tracker.awaitingMovement(KITCHEN, issued)).isFalse();
        assertThat(tracker.unconfirmed(KITCHEN, issued)).isFalse();
    }

    //a newer command takes the place of the older one: only the newer one is waited for
    @Test
    void should_wait_for_the_latest_command_only() {
        final IssuedCommand first = tracker.commanded(KITCHEN, CommandSource.DASHBOARD, false, Direction.CLOSE);
        final IssuedCommand second = tracker.commanded(KITCHEN, CommandSource.DASHBOARD, false, Direction.OPEN);

        assertThat(tracker.awaitingMovement(KITCHEN, first)).isFalse();
        assertThat(tracker.unconfirmed(KITCHEN, first)).isFalse();
        assertThat(tracker.awaitingMovement(KITCHEN, second)).isTrue();
    }

    //found in review: a close, an open at once, the open confirmed - the close is not reported
    //unconfirmed two minutes later, once nothing newer waits any more
    @Test
    void should_never_report_a_replaced_command_unconfirmed() {
        final IssuedCommand close = tracker.commanded(KITCHEN, CommandSource.DASHBOARD, false, Direction.CLOSE);
        final IssuedCommand open = tracker.commanded(KITCHEN, CommandSource.DASHBOARD, false, Direction.OPEN);
        tracker.observe(KITCHEN, BlindReadings.ofEatonStatus(1).orElseThrow()).as(StepVerifier::create).verifyComplete();
        clock.move(BlindStateTracker.COMMAND_WINDOW);

        assertThat(tracker.unconfirmed(KITCHEN, open)).isFalse();
        assertThat(tracker.unconfirmed(KITCHEN, close)).isFalse();
    }

    //a Shelly reports its movement and its position itself: no wait, no lone stop read as an end
    @Test
    void should_wait_for_no_movement_of_a_blind_other_than_eaton() {
        final IssuedCommand issued = tracker.commanded(DOOR, CommandSource.DASHBOARD, false, Direction.CLOSE);

        assertThat(issued.expectsMovement()).isFalse();
        assertThat(tracker.awaitingMovement(DOOR, issued)).isFalse();
        assertThat(tracker.unconfirmed(DOOR, issued)).isFalse();
    }

    // ---- reachability

    @Test
    void should_know_reachability_without_a_state_and_write_nothing() {
        tracker.reachability(DOOR, false, "the device left the broker").as(StepVerifier::create).verifyComplete();

        tracker.status(53).as(StepVerifier::create)
            .expectNext(BlindStatus.unknown().withReachable(false))
            .verifyComplete();
        verify(repository, never()).save(any());
        verify(repository, never()).touchLatest(anyLong(), any(), any());
    }

    @Test
    void should_take_a_reading_as_proof_that_the_blind_is_reachable() {
        tracker.reachability(DOOR, false, "timeout").as(StepVerifier::create).verifyComplete();
        tracker.observe(DOOR, reading(BlindState.CLOSED, 0, null)).as(StepVerifier::create).verifyComplete();

        tracker.status(53).map(BlindStatus::reachable).as(StepVerifier::create).expectNext(true).verifyComplete();
    }

    @Test
    void should_keep_the_state_when_the_blind_becomes_unreachable() {
        tracker.observe(DOOR, reading(BlindState.CLOSED, 0, null)).as(StepVerifier::create).verifyComplete();
        tracker.reachability(DOOR, false, "timeout").as(StepVerifier::create).verifyComplete();

        tracker.status(53).as(StepVerifier::create)
            .expectNext(new BlindStatus(BlindState.CLOSED, 0, null, NOW, "external", false))
            .verifyComplete();
        verify(repository).save(eq(new BlindStateEntity(null, 53, BlindState.CLOSED, 0, null, "external", NOW, NOW)));
    }

    private static BlindStateEntity entityOf(final BlindState state) {
        return new BlindStateEntity(1L, 53, state, null, null, "external", NOW, NOW);
    }

    private static BlindStatus known(final BlindState state, final Integer position, final Direction direction) {
        return new BlindStatus(state, position, direction, NOW.minusHours(1), "external", true);
    }

    private static BlindReading reading(final BlindState state, final Integer position, final String source) {
        return new BlindReading(state, position, null, source);
    }

    //a clock a test moves; Mockito cannot mock a Clock
    static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(final Instant now) {
            this.now = now;
        }

        void move(final Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return HOUSE;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
