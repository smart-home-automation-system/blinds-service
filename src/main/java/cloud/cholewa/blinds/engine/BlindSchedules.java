package cloud.cholewa.blinds.engine;

import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.schedule.BlindScheduleToday;
import cloud.cholewa.blinds.model.schedule.Schedule;
import cloud.cholewa.blinds.model.schedule.ScheduleToday;
import cloud.cholewa.blinds.registry.BlindRegistry;
import cloud.cholewa.blinds.service.schedule.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

//The schedules that move one blind and what each makes of today (HAS-232) - the read of
//ScheduleEngine.today for every schedule whose targets take in the blind, cut down to that blind
@Component
@RequiredArgsConstructor
public class BlindSchedules {

    private final BlindRegistry blindRegistry;
    private final ScheduleService scheduleService;
    private final ScheduleEngine scheduleEngine;

    public Mono<List<BlindScheduleToday>> of(final long id) {
        return blindRegistry.find(id)
            .flatMap(blind -> scheduleService.all()
                .flatMapMany(Flux::fromIterable)
                .filter(schedule -> !ScheduleEvaluator.blindsOf(schedule.targets(), List.of(blind)).isEmpty())
                .concatMap(schedule -> scheduleEngine.today(schedule).map(today -> view(schedule, today, blind)))
                .collectList());
    }

    private static BlindScheduleToday view(final Schedule schedule, final ScheduleToday today, final Blind blind) {
        final ScheduleToday.Target target = today.targets().stream()
            .filter(candidate -> candidate.deviceId() == blind.id())
            .findFirst()
            .orElse(null);
        return new BlindScheduleToday(
            schedule.id(),
            schedule.name(),
            Boolean.TRUE.equals(schedule.enabled()),
            schedule.action(),
            schedule.position(),
            today.window(),
            today.decision(),
            target == null ? null : target.firedAt(),
            target == null ? null : target.outcome());
    }
}
