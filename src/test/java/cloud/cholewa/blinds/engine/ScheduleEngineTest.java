package cloud.cholewa.blinds.engine;

import cloud.cholewa.blinds.database.model.ScheduleRunEntity;
import cloud.cholewa.blinds.database.repository.ScheduleRunRepository;
import cloud.cholewa.blinds.engine.DayTypes.TodayType;
import cloud.cholewa.blinds.engine.ScheduleEvaluator.ConditionStatus;
import cloud.cholewa.blinds.engine.ScheduleEvaluator.Verdict;
import cloud.cholewa.blinds.engine.WeatherNow.Observation;
import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindCommand;
import cloud.cholewa.blinds.model.CommandSource;
import cloud.cholewa.blinds.model.WindowState;
import cloud.cholewa.blinds.model.schedule.ConditionMetric;
import cloud.cholewa.blinds.model.schedule.ConditionOperator;
import cloud.cholewa.blinds.model.schedule.Schedule;
import cloud.cholewa.blinds.model.schedule.ScheduleAction;
import cloud.cholewa.blinds.model.schedule.ScheduleCondition;
import cloud.cholewa.blinds.model.schedule.ScheduleDayType;
import cloud.cholewa.blinds.model.schedule.ScheduleTarget;
import cloud.cholewa.blinds.model.schedule.ScheduleToday;
import cloud.cholewa.blinds.model.schedule.WindowEnd;
import cloud.cholewa.blinds.registry.BlindRegistry;
import cloud.cholewa.blinds.service.BlindsCommandService;
import cloud.cholewa.blinds.service.schedule.ScheduleFixtures;
import cloud.cholewa.blinds.service.schedule.ScheduleService;
import cloud.cholewa.blinds.window.WindowSensors;
import cloud.cholewa.home.model.DayType;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The engine around the evaluator: what it reads, what it fires, what it stores and what it keeps
//from one pass to the next. One Mono of pass() is subscribed again for every minute, as Spring does
class ScheduleEngineTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final LocalDateTime FIVE_PM = DAY.atTime(17, 0);
    private static final Blind KITCHEN = ScheduleEvaluatorTest.blind(17, RoomName.KITCHEN);
    private static final Blind OFFICE = ScheduleEvaluatorTest.blind(52, RoomName.OFFICE);

    private final AtomicReference<LocalDateTime> now = new AtomicReference<>(FIVE_PM);
    private final ScheduleService scheduleService = mock(ScheduleService.class);
    private final ScheduleRunRepository runRepository = mock(ScheduleRunRepository.class);
    private final BlindRegistry blindRegistry = mock(BlindRegistry.class);
    private final BlindsCommandService commandService = mock(BlindsCommandService.class);
    private final DayTypes dayTypes = mock(DayTypes.class);
    private final SunCalendar sunCalendar = mock(SunCalendar.class);
    private final WeatherClient weatherClient = mock(WeatherClient.class);
    private final WindowSensors windowSensors = mock(WindowSensors.class);
    private final List<ScheduleRunEntity> stored = new ArrayList<>();

    private final ScheduleEngine sut = new ScheduleEngine(scheduleService, runRepository, blindRegistry, commandService,
        dayTypes, sunCalendar, weatherClient, new HoldTracker(), windowSensors, new Clock() {
        @Override
        public ZoneId getZone() {
            return HOUSE;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get().atZone(HOUSE).toInstant();
        }
    });

    @BeforeEach
    void setUp() {
        when(blindRegistry.current()).thenReturn(List.of(KITCHEN, OFFICE));
        when(dayTypes.of(any())).thenAnswer(call -> Mono.just(
            new TodayType(call.getArgument(0), DayType.WORKDAY, true, Instant.EPOCH)));
        when(sunCalendar.of(any())).thenAnswer(call -> new SunDay(call.getArgument(0), LocalTime.of(7, 3), LocalTime.of(17, 47)));
        darkness(100);
        when(commandService.move(anyLong(), any(), any())).thenReturn(Mono.empty());
        when(commandService.moveTo(anyLong(), anyInt(), any())).thenReturn(Mono.empty());
        when(runRepository.findByFiredOnIn(any())).thenAnswer(call -> Flux.fromIterable(List.copyOf(stored))
            .filter(run -> call.<Collection<LocalDate>>getArgument(0).contains(run.firedOn())));
        when(windowSensors.stateOf(any(), any())).thenReturn(WindowState.NO_SENSOR);
        when(runRepository.findByScheduleIdAndFiredOnOrderById(anyLong(), any())).thenAnswer(call -> Flux.fromIterable(List.copyOf(stored))
            .filter(run -> run.scheduleId() == (long) call.getArgument(0) && run.firedOn().equals(call.getArgument(1))));
        when(runRepository.save(any())).thenAnswer(call -> {
            stored.add(call.getArgument(0));
            return Mono.just(call.getArgument(0));
        });
    }

    @Test
    void should_fire_a_schedule_once_and_store_the_run() {
        schedules(closeTheKitchen(0));

        final Mono<Void> pass = sut.pass();
        pass.as(StepVerifier::create).verifyComplete();
        now.set(FIVE_PM.plusMinutes(1));
        pass.as(StepVerifier::create).verifyComplete();

        verify(commandService, times(1)).move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE);
        assertThat(stored).singleElement().satisfies(run -> {
            assertThat(run.scheduleId()).isEqualTo(5);
            assertThat(run.deviceId()).isEqualTo(17);
            assertThat(run.firedOn()).isEqualTo(DAY);
            assertThat(run.firedAt()).isEqualTo(FIVE_PM);
            assertThat(run.outcome()).isEqualTo(RunOutcome.MOVED);
        });
    }

    //a restart forgets the memory; the rows of the day keep the blind from moving twice
    @Test
    void should_not_fire_again_for_a_run_stored_before_a_restart() {
        schedules(closeTheKitchen(0));
        stored.add(new ScheduleRunEntity(1L, 5, 17, DAY, DAY.atTime(16, 30), RunOutcome.FAILED));

        sut.pass().as(StepVerifier::create).verifyComplete();

        verify(commandService, never()).move(anyLong(), any(), any());
    }

    //the hold is counted from the first pass that saw the condition true, across passes
    @Test
    void should_fire_once_the_condition_has_held_for_its_hold() {
        schedules(closeTheKitchen(10));
        final Mono<Void> pass = sut.pass();

        pass.as(StepVerifier::create).verifyComplete();
        now.set(FIVE_PM.plusMinutes(9));
        darkness(120, now.get());
        pass.as(StepVerifier::create).verifyComplete();
        verify(commandService, never()).move(anyLong(), any(), any());

        now.set(FIVE_PM.plusMinutes(10));
        darkness(150, now.get());
        pass.as(StepVerifier::create).verifyComplete();
        verify(commandService, times(1)).move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE);
    }

    @Test
    void should_start_the_hold_again_after_a_break() {
        schedules(closeTheKitchen(10));
        final Mono<Void> pass = sut.pass();

        pass.as(StepVerifier::create).verifyComplete();
        now.set(FIVE_PM.plusMinutes(5));
        darkness(500, now.get());
        pass.as(StepVerifier::create).verifyComplete();
        now.set(FIVE_PM.plusMinutes(10));
        darkness(100, now.get());
        pass.as(StepVerifier::create).verifyComplete();

        verify(commandService, never()).move(anyLong(), any(), any());
    }

    //it may have moved: a failure is a run and is never repeated
    @Test
    void should_store_a_failed_command_and_not_repeat_it() {
        schedules(closeTheKitchen(0));
        when(commandService.move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE))
            .thenReturn(Mono.error(BlindsException.deviceFailed(KITCHEN, new IllegalStateException("timeout"))));

        final Mono<Void> pass = sut.pass();
        pass.as(StepVerifier::create).verifyComplete();
        now.set(FIVE_PM.plusMinutes(1));
        pass.as(StepVerifier::create).verifyComplete();

        verify(commandService, times(1)).move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE);
        assertThat(stored).extracting(ScheduleRunEntity::outcome).containsExactly(RunOutcome.FAILED);
    }

    @Test
    void should_try_again_in_the_next_minute_a_blind_the_protection_held_back() {
        schedules(closeTheKitchen(0));
        when(commandService.move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE))
            .thenReturn(Mono.error(BlindsException.protectionActive(KITCHEN, "wind")))
            .thenReturn(Mono.empty());

        final Mono<Void> pass = sut.pass();
        pass.as(StepVerifier::create).verifyComplete();
        assertThat(stored).isEmpty();

        now.set(FIVE_PM.plusMinutes(1));
        darkness(100, now.get());
        pass.as(StepVerifier::create).verifyComplete();

        verify(commandService, times(2)).move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE);
        assertThat(stored).extracting(ScheduleRunEntity::outcome).containsExactly(RunOutcome.MOVED);
    }

    @Test
    void should_store_a_position_for_a_blind_without_one_as_unsupported() {
        schedules(withId(7, ScheduleFixtures.sunsetClose()
            .from(WindowEnd.clock(LocalTime.of(16, 0)))
            .targets(List.of(ScheduleTarget.blind(17)))
            .conditions(List.of())
            .action(ScheduleAction.POSITION)
            .position(30)
            .build()));
        when(commandService.moveTo(17, 30, CommandSource.SCHEDULE))
            .thenReturn(Mono.error(BlindsException.unsupportedPosition(KITCHEN)));

        sut.pass().as(StepVerifier::create).verifyComplete();

        assertThat(stored).extracting(ScheduleRunEntity::outcome).containsExactly(RunOutcome.UNSUPPORTED);
    }

    //the blind is remembered before its command is sent, so a lost row does not move it twice
    @Test
    void should_not_fire_again_when_the_run_could_not_be_stored() {
        schedules(closeTheKitchen(0));
        doReturn(Mono.error(new IllegalStateException("connection lost"))).when(runRepository).save(any());

        final Mono<Void> pass = sut.pass();
        pass.as(StepVerifier::create).verifyComplete();
        now.set(FIVE_PM.plusMinutes(1));
        pass.as(StepVerifier::create).verifyComplete();

        verify(commandService, times(1)).move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE);
    }

    //without the runs of the day nothing may fire - and the pass still completes
    @Test
    void should_fire_nothing_when_the_runs_of_the_day_cannot_be_read() {
        schedules(closeTheKitchen(0));
        when(runRepository.findByFiredOnIn(any())).thenReturn(Flux.error(new IllegalStateException("pool exhausted")));

        sut.pass().as(StepVerifier::create).verifyComplete();

        verify(commandService, never()).move(anyLong(), any(), any());
    }

    //a minute the engine did not judge breaks the hold: later, never earlier
    @Test
    void should_start_the_holds_again_after_a_pass_without_the_runs() {
        schedules(closeTheKitchen(5));
        final Mono<Void> pass = sut.pass();
        pass.as(StepVerifier::create).verifyComplete();

        when(runRepository.findByFiredOnIn(any())).thenReturn(Flux.error(new IllegalStateException("pool exhausted")));
        now.set(FIVE_PM.plusMinutes(1));
        darkness(100, now.get());
        pass.as(StepVerifier::create).verifyComplete();

        when(runRepository.findByFiredOnIn(any())).thenAnswer(call -> Flux.fromIterable(List.copyOf(stored)));
        now.set(FIVE_PM.plusMinutes(5));
        darkness(100, now.get());
        pass.as(StepVerifier::create).verifyComplete();
        verify(commandService, never()).move(anyLong(), any(), any());

        now.set(FIVE_PM.plusMinutes(10));
        darkness(100, now.get());
        pass.as(StepVerifier::create).verifyComplete();
        verify(commandService).move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE);
    }

    //an error that is no BlindsException is a failed run, and the other blinds still move
    @Test
    void should_go_on_with_the_next_blind_after_an_unexpected_error() {
        schedules(withId(8, ScheduleFixtures.sunsetClose()
            .from(WindowEnd.clock(LocalTime.of(16, 0)))
            .targets(List.of(ScheduleTarget.blind(17), ScheduleTarget.blind(52)))
            .conditions(List.of())
            .build()));
        when(commandService.move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE))
            .thenReturn(Mono.error(new IllegalStateException("unexpected")));

        sut.pass().as(StepVerifier::create).verifyComplete();

        assertThat(stored).extracting(ScheduleRunEntity::deviceId, ScheduleRunEntity::outcome).containsExactly(
            org.assertj.core.groups.Tuple.tuple(17L, RunOutcome.FAILED),
            org.assertj.core.groups.Tuple.tuple(52L, RunOutcome.MOVED));
    }

    //an unknown input stops the schedules that need it, and no others
    @Test
    void should_fire_the_schedules_without_weather_when_the_weather_cannot_be_read() {
        final Schedule openTheOffice = withId(6, ScheduleFixtures.sunsetClose()
            .from(WindowEnd.clock(LocalTime.of(16, 0)))
            .targets(List.of(ScheduleTarget.room(RoomName.OFFICE)))
            .conditions(List.of())
            .action(ScheduleAction.OPEN)
            .build());
        schedules(closeTheKitchen(0), openTheOffice);
        when(weatherClient.current()).thenReturn(Mono.error(new InputCallException("weather-service answered 503")));

        sut.pass().as(StepVerifier::create).verifyComplete();

        verify(commandService, never()).move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE);
        verify(commandService).move(52, BlindCommand.OPEN, CommandSource.SCHEDULE);
    }

    @Test
    void should_move_the_blinds_of_a_schedule_one_after_the_other_and_each_once() {
        schedules(withId(8, ScheduleFixtures.sunsetClose()
            .from(WindowEnd.clock(LocalTime.of(16, 0)))
            .targets(List.of(ScheduleTarget.blind(52), ScheduleTarget.room(RoomName.KITCHEN), ScheduleTarget.room(RoomName.OFFICE)))
            .conditions(List.of())
            .build()));

        sut.pass().as(StepVerifier::create).verifyComplete();

        assertThat(stored).extracting(ScheduleRunEntity::deviceId).containsExactly(52L, 17L);
    }

    @Test
    void should_not_fire_a_disabled_schedule_nor_one_on_another_day_type() {
        schedules(withId(9, ScheduleFixtures.sunsetClose().enabled(false).conditions(List.of()).build()),
            withId(10, ScheduleFixtures.sunsetClose().dayType(ScheduleDayType.DAY_OFF).conditions(List.of())
                .from(WindowEnd.clock(LocalTime.of(16, 0))).build()));

        sut.pass().as(StepVerifier::create).verifyComplete();

        verify(commandService, never()).move(anyLong(), any(), any());
    }

    @Test
    void should_read_no_input_when_there_is_no_schedule() {
        when(scheduleService.all()).thenReturn(Mono.just(List.of()));

        sut.pass().as(StepVerifier::create).verifyComplete();

        verify(weatherClient, never()).current();
        verify(dayTypes, never()).of(any());
    }

    //the read shows the window, the conditions as the last pass left them and the blinds moved
    @Test
    void should_show_a_schedule_today() {
        final Schedule schedule = closeTheKitchen(10);
        schedules(schedule);
        sut.pass().as(StepVerifier::create).verifyComplete();
        now.set(FIVE_PM.plusMinutes(4));

        sut.today(schedule).as(StepVerifier::create)
            .assertNext(today -> {
                assertThat(today.date()).isEqualTo(DAY);
                assertThat(today.dayType()).isEqualTo("workday");
                assertThat(today.dayTypeFromCalendar()).isTrue();
                assertThat(today.runsToday()).isTrue();
                assertThat(today.window()).isEqualTo(new ScheduleToday.Window(LocalTime.of(16, 0), LocalTime.of(18, 0), null));
                assertThat(today.conditions()).singleElement().satisfies(condition -> {
                    assertThat(condition.current()).isEqualTo(100);
                    assertThat(condition.measuredAt()).isEqualTo(FIVE_PM);
                    assertThat(condition.trueSince()).isEqualTo(FIVE_PM);
                    assertThat(condition.state()).isEqualTo(ConditionStatus.HOLDING);
                });
                assertThat(today.targets()).extracting(ScheduleToday.Target::deviceId).containsExactly(17L);
                assertThat(today.decision()).isEqualTo(Verdict.WAITING);
            })
            .verifyComplete();
    }

    @Test
    void should_show_the_blinds_a_schedule_moved_today() {
        final Schedule schedule = closeTheKitchen(0);
        schedules(schedule);
        stored.add(new ScheduleRunEntity(1L, 5, 99, DAY, DAY.atTime(16, 1), RunOutcome.MOVED));
        sut.pass().as(StepVerifier::create).verifyComplete();

        sut.today(schedule).as(StepVerifier::create)
            .assertNext(today -> {
                assertThat(today.targets()).containsExactly(
                    new ScheduleToday.Target(17, "Blind 17", "kitchen", WindowState.NO_SENSOR, null, FIVE_PM, RunOutcome.MOVED),
                    new ScheduleToday.Target(99, null, null, null, null, DAY.atTime(16, 1), RunOutcome.MOVED));
                assertThat(today.decision()).isEqualTo(Verdict.FIRED);
            })
            .verifyComplete();
    }

    @Test
    void should_complete_a_pass_that_fails() {
        when(scheduleService.all()).thenReturn(Mono.error(new IllegalStateException("database away")));

        sut.pass().as(StepVerifier::create).verifyComplete();

        final ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
        verify(commandService, never()).move(ids.capture(), any(), any());
    }

    //--- the windows of the blinds (HAS-290)

    //per blind, never per room: the kitchen window is open, the office has no sensor - the office closes
    @Test
    void should_judge_window_closed_by_the_window_of_each_blind() {
        schedules(closeWithTheWindow(true, ScheduleAction.CLOSE, null, List.of(ScheduleTarget.room(RoomName.KITCHEN),
            ScheduleTarget.room(RoomName.OFFICE))));
        when(windowSensors.stateOf(eq(KITCHEN), any())).thenReturn(WindowState.OPEN);
        when(windowSensors.stateOf(eq(OFFICE), any())).thenReturn(WindowState.NO_SENSOR);

        sut.pass().as(StepVerifier::create).verifyComplete();

        verify(commandService).move(52, BlindCommand.CLOSE, CommandSource.SCHEDULE);
        verify(commandService, never()).move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE);
    }

    //a sensor that is not known never passes for a closed window
    @Test
    void should_not_close_a_blind_whose_window_is_not_known() {
        schedules(closeWithTheWindow(true, ScheduleAction.CLOSE, null, List.of(ScheduleTarget.room(RoomName.KITCHEN))));
        when(windowSensors.stateOf(eq(KITCHEN), any())).thenReturn(WindowState.UNKNOWN);

        sut.pass().as(StepVerifier::create).verifyComplete();

        verify(commandService, never()).move(anyLong(), any(), any());
    }

    //The evening of the owner: the window open sends the blind to 30, and once the window is closed
    //later in the evening the close schedule closes it fully - once a day is per schedule and blind
    @Test
    void should_send_a_blind_to_30_with_the_window_open_and_close_it_once_the_window_is_closed() {
        final Schedule ajar = withId(6, closeWithTheWindow(false, ScheduleAction.POSITION, 30,
            List.of(ScheduleTarget.room(RoomName.KITCHEN))));
        final Schedule close = withId(7, closeWithTheWindow(true, ScheduleAction.CLOSE, null,
            List.of(ScheduleTarget.room(RoomName.KITCHEN))));
        schedules(ajar, close);
        when(windowSensors.stateOf(eq(KITCHEN), any())).thenReturn(WindowState.OPEN);
        final Mono<Void> pass = sut.pass();

        pass.as(StepVerifier::create).verifyComplete();
        verify(commandService).moveTo(17, 30, CommandSource.SCHEDULE);
        verify(commandService, never()).move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE);

        now.set(FIVE_PM.plusMinutes(20));
        darkness(100, now.get());
        when(windowSensors.stateOf(eq(KITCHEN), any())).thenReturn(WindowState.CLOSED);
        pass.as(StepVerifier::create).verifyComplete();
        verify(commandService).move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE);

        //opened and closed again: neither fires a second time today
        now.set(FIVE_PM.plusMinutes(30));
        darkness(100, now.get());
        when(windowSensors.stateOf(eq(KITCHEN), any())).thenReturn(WindowState.OPEN);
        pass.as(StepVerifier::create).verifyComplete();
        verify(commandService, times(1)).moveTo(17, 30, CommandSource.SCHEDULE);
        verify(commandService, times(1)).move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE);
        assertThat(stored).extracting(ScheduleRunEntity::scheduleId).containsExactly(6L, 7L);
    }

    //the hold of window closed is the blind's own
    @Test
    void should_hold_window_closed_for_each_blind_on_its_own() {
        schedules(withId(8, ScheduleFixtures.sunsetClose()
            .from(WindowEnd.clock(LocalTime.of(16, 0)))
            .to(WindowEnd.clock(LocalTime.of(18, 0)))
            .targets(List.of(ScheduleTarget.room(RoomName.KITCHEN), ScheduleTarget.room(RoomName.OFFICE)))
            .conditions(List.of(ScheduleCondition.is(ConditionMetric.WINDOW_CLOSED, true, 5)))
            .build()));
        when(windowSensors.stateOf(eq(KITCHEN), any())).thenReturn(WindowState.CLOSED);
        when(windowSensors.stateOf(eq(OFFICE), any())).thenReturn(WindowState.OPEN);
        final Mono<Void> pass = sut.pass();
        pass.as(StepVerifier::create).verifyComplete();

        now.set(FIVE_PM.plusMinutes(3));
        when(windowSensors.stateOf(eq(OFFICE), any())).thenReturn(WindowState.CLOSED);
        pass.as(StepVerifier::create).verifyComplete();
        now.set(FIVE_PM.plusMinutes(5));
        pass.as(StepVerifier::create).verifyComplete();
        verify(commandService).move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE);
        verify(commandService, never()).move(52, BlindCommand.CLOSE, CommandSource.SCHEDULE);

        now.set(FIVE_PM.plusMinutes(8));
        pass.as(StepVerifier::create).verifyComplete();
        verify(commandService).move(52, BlindCommand.CLOSE, CommandSource.SCHEDULE);
    }

    @Test
    void should_show_the_window_of_each_target_today() {
        final Schedule schedule = withId(9, closeWithTheWindow(true, ScheduleAction.CLOSE, null,
            List.of(ScheduleTarget.room(RoomName.KITCHEN), ScheduleTarget.room(RoomName.OFFICE))));
        schedules(schedule);
        when(windowSensors.stateOf(eq(KITCHEN), any())).thenReturn(WindowState.OPEN);
        when(windowSensors.stateOf(eq(OFFICE), any())).thenReturn(WindowState.UNKNOWN);

        sut.pass().as(StepVerifier::create).verifyComplete();

        sut.today(schedule).as(StepVerifier::create)
            .assertNext(today -> {
                assertThat(today.conditions()).extracting(ScheduleToday.Condition::state)
                    .containsExactly(ConditionStatus.MET, ConditionStatus.PER_BLIND);
                assertThat(today.targets()).extracting(ScheduleToday.Target::window)
                    .containsExactly(WindowState.OPEN, WindowState.UNKNOWN);
                assertThat(today.targets()).extracting(ScheduleToday.Target::windowClosed)
                    .containsExactly(ConditionStatus.NOT_MET, ConditionStatus.UNKNOWN);
                assertThat(today.decision()).isEqualTo(Verdict.WAITING);
            })
            .verifyComplete();
    }

    //--- a window across midnight (HAS-290)

    //21:00 to 06:00: the conditions first hold after midnight - the run counts for the night that
    //opened the day before, and the blind is moved once that night, then again the next night
    @Test
    void should_fire_a_night_window_once_per_night_also_after_midnight() {
        schedules(night());
        darkness(1000, DAY.atTime(22, 0));
        now.set(DAY.atTime(22, 0));
        final Mono<Void> pass = sut.pass();
        pass.as(StepVerifier::create).verifyComplete();
        verify(commandService, never()).move(anyLong(), any(), any());

        now.set(DAY.plusDays(1).atTime(1, 0));
        darkness(100, now.get());
        pass.as(StepVerifier::create).verifyComplete();
        now.set(DAY.plusDays(1).atTime(1, 1));
        darkness(100, now.get());
        pass.as(StepVerifier::create).verifyComplete();
        now.set(DAY.plusDays(1).atTime(5, 59));
        darkness(100, now.get());
        pass.as(StepVerifier::create).verifyComplete();

        verify(commandService, times(1)).move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE);
        assertThat(stored).singleElement().satisfies(run -> {
            assertThat(run.firedOn()).isEqualTo(DAY);
            assertThat(run.firedAt()).isEqualTo(DAY.plusDays(1).atTime(1, 0));
        });

        now.set(DAY.plusDays(1).atTime(21, 0));
        darkness(100, now.get());
        pass.as(StepVerifier::create).verifyComplete();
        verify(commandService, times(2)).move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE);
        assertThat(stored).extracting(ScheduleRunEntity::firedOn).containsExactly(DAY, DAY.plusDays(1));
    }

    //a run stored before a restart, in the evening, keeps the night from firing again after midnight
    @Test
    void should_not_fire_a_night_again_after_midnight_for_a_run_of_the_evening() {
        schedules(night());
        stored.add(new ScheduleRunEntity(1L, 10, 17, DAY, DAY.atTime(21, 5), RunOutcome.MOVED));
        now.set(DAY.plusDays(1).atTime(0, 30));
        darkness(100, now.get());

        sut.pass().as(StepVerifier::create).verifyComplete();

        verify(commandService, never()).move(anyLong(), any(), any());
    }

    //the night belongs to the day it opened on: a night of a workday schedule after a Friday runs,
    //after a Saturday it does not
    @Test
    void should_judge_a_night_by_the_type_of_the_day_it_opened_on() {
        schedules(withId(11, ScheduleFixtures.sunsetClose()
            .dayType(ScheduleDayType.DAY_OFF)
            .from(WindowEnd.clock(LocalTime.of(21, 0)))
            .to(WindowEnd.clock(LocalTime.of(6, 0)))
            .targets(List.of(ScheduleTarget.room(RoomName.KITCHEN)))
            .conditions(List.of())
            .build()));
        when(dayTypes.of(any())).thenAnswer(call -> Mono.just(new TodayType(call.getArgument(0),
            call.getArgument(0).equals(DAY) ? DayType.DAY_OFF : DayType.WORKDAY, true, Instant.EPOCH)));
        now.set(DAY.plusDays(1).atTime(2, 0));

        sut.pass().as(StepVerifier::create).verifyComplete();

        verify(commandService).move(17, BlindCommand.CLOSE, CommandSource.SCHEDULE);
        assertThat(stored).singleElement().satisfies(run -> assertThat(run.firedOn()).isEqualTo(DAY));
    }

    @Test
    void should_show_a_night_window_by_the_day_it_opened_on() {
        final Schedule schedule = night();
        schedules(schedule);
        now.set(DAY.plusDays(1).atTime(2, 0));

        sut.today(schedule).as(StepVerifier::create)
            .assertNext(today -> {
                assertThat(today.date()).isEqualTo(DAY);
                assertThat(today.window()).isEqualTo(new ScheduleToday.Window(LocalTime.of(21, 0), LocalTime.of(6, 0), true));
            })
            .verifyComplete();
    }

    //the kitchen from 21:00 to 06:00 when darker than 200 lx
    private static Schedule night() {
        return withId(10, ScheduleFixtures.sunsetClose()
            .from(WindowEnd.clock(LocalTime.of(21, 0)))
            .to(WindowEnd.clock(LocalTime.of(6, 0)))
            .targets(List.of(ScheduleTarget.room(RoomName.KITCHEN)))
            .conditions(List.of(ScheduleCondition.compared(ConditionMetric.ILLUMINANCE, ConditionOperator.LT,
                BigDecimal.valueOf(200), 0)))
            .build());
    }

    //16:00 to 18:00, darker than 200 lx and the window closed as given
    private static Schedule closeWithTheWindow(final boolean closed, final ScheduleAction action, final Integer position,
                                               final List<ScheduleTarget> targets) {
        return withId(5, ScheduleFixtures.sunsetClose()
            .from(WindowEnd.clock(LocalTime.of(16, 0)))
            .to(WindowEnd.clock(LocalTime.of(18, 0)))
            .targets(targets)
            .conditions(List.of(
                ScheduleCondition.compared(ConditionMetric.ILLUMINANCE, ConditionOperator.LT, BigDecimal.valueOf(200), 0),
                ScheduleCondition.is(ConditionMetric.WINDOW_CLOSED, closed, 0)))
            .action(action)
            .position(position)
            .build());
    }

    //the kitchen between 16:00 and 18:00 when darker than 200 lx for the hold given
    private static Schedule closeTheKitchen(final int holdMinutes) {
        return withId(5, ScheduleFixtures.sunsetClose()
            .from(WindowEnd.clock(LocalTime.of(16, 0)))
            .to(WindowEnd.clock(LocalTime.of(18, 0)))
            .targets(List.of(ScheduleTarget.room(RoomName.KITCHEN)))
            .conditions(List.of(ScheduleCondition.compared(ConditionMetric.ILLUMINANCE, ConditionOperator.LT,
                BigDecimal.valueOf(200), holdMinutes)))
            .build());
    }

    private static Schedule withId(final long id, final Schedule schedule) {
        return new Schedule(id, schedule.name(), schedule.enabled(), schedule.dayType(), schedule.window(),
            schedule.targets(), schedule.conditions(), schedule.action(), schedule.position(), null, null);
    }

    private void schedules(final Schedule... schedules) {
        when(scheduleService.all()).thenReturn(Mono.just(List.of(schedules)));
    }

    private void darkness(final int lux) {
        darkness(lux, FIVE_PM);
    }

    private void darkness(final int lux, final LocalDateTime measuredAt) {
        when(weatherClient.current()).thenReturn(Mono.just(Map.of(ConditionMetric.ILLUMINANCE,
            new Observation(lux, measuredAt, false))));
    }
}
