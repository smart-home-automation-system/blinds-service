package cloud.cholewa.blinds.engine;

import cloud.cholewa.blinds.engine.ScheduleEvaluator.Verdict;
import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.WindowState;
import cloud.cholewa.blinds.model.schedule.BlindScheduleToday;
import cloud.cholewa.blinds.model.schedule.Schedule;
import cloud.cholewa.blinds.model.schedule.ScheduleAction;
import cloud.cholewa.blinds.model.schedule.ScheduleTarget;
import cloud.cholewa.blinds.model.schedule.ScheduleToday;
import cloud.cholewa.blinds.registry.BlindRegistry;
import cloud.cholewa.blinds.service.schedule.ScheduleFixtures;
import cloud.cholewa.blinds.service.schedule.ScheduleService;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The schedules that move one blind (HAS-232): those whose targets take it in, by its room or by its
//id, each cut down to that blind
class BlindSchedulesTest {

    private static final Blind KITCHEN = ScheduleEvaluatorTest.blind(17, RoomName.KITCHEN);
    private static final LocalDateTime FIRED_AT = LocalDateTime.of(2026, 10, 17, 17, 2);

    private final BlindRegistry blindRegistry = mock(BlindRegistry.class);
    private final ScheduleService scheduleService = mock(ScheduleService.class);
    private final ScheduleEngine scheduleEngine = mock(ScheduleEngine.class);
    private final BlindSchedules sut = new BlindSchedules(blindRegistry, scheduleService, scheduleEngine);

    @Test
    void should_answer_the_schedules_that_move_the_blind() {
        final Schedule byRoom = schedule(5, ScheduleTarget.room(RoomName.KITCHEN));
        final Schedule byId = schedule(6, ScheduleTarget.blind(17));
        final Schedule other = schedule(7, ScheduleTarget.room(RoomName.OFFICE));
        when(blindRegistry.find(17)).thenReturn(Mono.just(KITCHEN));
        when(scheduleService.all()).thenReturn(Mono.just(List.of(byRoom, byId, other)));
        when(blindRegistry.current()).thenReturn(List.of(KITCHEN));
        when(scheduleEngine.today(byRoom)).thenReturn(Mono.just(today(5, new ScheduleToday.Target(
            17, "Blind 17", "kitchen", WindowState.NO_SENSOR, null, FIRED_AT, RunOutcome.PAUSED))));
        when(scheduleEngine.today(byId)).thenReturn(Mono.just(today(6, new ScheduleToday.Target(
            17, "Blind 17", "kitchen", WindowState.NO_SENSOR, null, null, null))));

        sut.of(17).as(StepVerifier::create)
            .expectNext(List.of(
                new BlindScheduleToday(5, "Evening close", true, ScheduleAction.CLOSE, null, WINDOW, Verdict.FIRED,
                    FIRED_AT, RunOutcome.PAUSED),
                new BlindScheduleToday(6, "Evening close", true, ScheduleAction.CLOSE, null, WINDOW, Verdict.FIRED,
                    null, null)))
            .verifyComplete();
        verify(scheduleEngine, never()).today(other);
    }

    @Test
    void should_answer_404_for_a_blind_the_registry_does_not_know() {
        when(blindRegistry.find(99)).thenReturn(Mono.error(BlindsException.notFound("no blind with id 99")));

        sut.of(99).as(StepVerifier::create).expectError(BlindsException.class).verify();
        verify(scheduleEngine, never()).today(any());
    }

    private static final ScheduleToday.Window WINDOW = new ScheduleToday.Window(LocalTime.of(16, 0), LocalTime.of(18, 0), null);

    private static ScheduleToday today(final long id, final ScheduleToday.Target target) {
        return new ScheduleToday(id, LocalDate.of(2026, 10, 17), "workday", true, true, WINDOW, List.of(), List.of(target),
            Verdict.FIRED);
    }

    private static Schedule schedule(final long id, final ScheduleTarget target) {
        final Schedule built = ScheduleFixtures.sunsetClose().targets(List.of(target)).build();
        return new Schedule(id, built.name(), built.enabled(), built.dayType(), built.window(), built.targets(),
            built.conditions(), built.action(), built.position(), null, null);
    }
}
