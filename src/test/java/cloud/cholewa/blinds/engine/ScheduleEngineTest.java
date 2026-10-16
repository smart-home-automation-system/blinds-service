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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
    private final List<ScheduleRunEntity> stored = new ArrayList<>();

    private final ScheduleEngine sut = new ScheduleEngine(scheduleService, runRepository, blindRegistry, commandService,
        dayTypes, sunCalendar, weatherClient, new HoldTracker(), new Clock() {
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
        when(runRepository.findByFiredOn(any())).thenAnswer(call -> Flux.fromIterable(List.copyOf(stored))
            .filter(run -> run.firedOn().equals(call.getArgument(0))));
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
        when(runRepository.findByFiredOn(any())).thenReturn(Flux.error(new IllegalStateException("pool exhausted")));

        sut.pass().as(StepVerifier::create).verifyComplete();

        verify(commandService, never()).move(anyLong(), any(), any());
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
                assertThat(today.window()).isEqualTo(new ScheduleToday.Window(LocalTime.of(16, 0), LocalTime.of(18, 0)));
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
                    new ScheduleToday.Target(17, "Blind 17", "kitchen", FIVE_PM, RunOutcome.MOVED),
                    new ScheduleToday.Target(99, null, null, DAY.atTime(16, 1), RunOutcome.MOVED));
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
