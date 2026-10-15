package cloud.cholewa.blinds.service.schedule;

import cloud.cholewa.blinds.database.model.ScheduleConditionEntity;
import cloud.cholewa.blinds.database.model.ScheduleEntity;
import cloud.cholewa.blinds.database.model.ScheduleTargetEntity;
import cloud.cholewa.blinds.database.repository.ScheduleConditionRepository;
import cloud.cholewa.blinds.database.repository.ScheduleRepository;
import cloud.cholewa.blinds.database.repository.ScheduleTargetRepository;
import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.schedule.Schedule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

//The schedules of the blinds, stored in three tables. A write is one transaction: a schedule stays
//as it was while a write fails, its targets and conditions included. The rules are checked before
//anything is written; the answer of a write is the schedule read back after the commit.
//The reads go one after the other: the pool has two connections, and three reads at once would
//wait for a third
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private static final Sort BY_ID = Sort.by("id");

    private final ScheduleRepository scheduleRepository;
    private final ScheduleTargetRepository targetRepository;
    private final ScheduleConditionRepository conditionRepository;
    private final TransactionalOperator transactionalOperator;
    private final Clock clock;

    //every schedule, in the order they were created
    public Mono<List<Schedule>> all() {
        return scheduleRepository.findAll(BY_ID).collectList()
            .flatMap(schedules -> targetRepository.findAllByOrderById().collectList()
                .flatMap(targets -> conditionRepository.findAllByOrderById().collectList()
                    .map(conditions -> {
                        final Map<Long, List<ScheduleTargetEntity>> targetsOf = targets.stream()
                            .collect(Collectors.groupingBy(ScheduleTargetEntity::scheduleId));
                        final Map<Long, List<ScheduleConditionEntity>> conditionsOf = conditions.stream()
                            .collect(Collectors.groupingBy(ScheduleConditionEntity::scheduleId));
                        return schedules.stream()
                            .map(schedule -> ScheduleMapper.toSchedule(schedule,
                                targetsOf.getOrDefault(schedule.id(), List.of()),
                                conditionsOf.getOrDefault(schedule.id(), List.of())))
                            .toList();
                    })));
    }

    public Mono<Schedule> get(final long id) {
        return existing(id).flatMap(this::withChildren);
    }

    //a new schedule is as enabled as its body says
    public Mono<Schedule> create(final Schedule schedule) {
        return Mono.fromRunnable(() -> ScheduleRules.check(schedule))
            .then(Mono.defer(() -> {
                final LocalDateTime now = now();
                return scheduleRepository.save(ScheduleMapper.toEntity(null, schedule, now, now))
                    .flatMap(saved -> writeChildren(saved.id(), schedule).thenReturn(saved.id()));
            }))
            .as(transactionalOperator::transactional)
            .doOnNext(id -> log.info("Schedule {} ({}) created", id, schedule.name()))
            .flatMap(this::get);
    }

    //whole, enabled included; the id and the time it was created survive it
    public Mono<Schedule> replace(final long id, final Schedule schedule) {
        return Mono.fromRunnable(() -> ScheduleRules.check(schedule))
            .then(existing(id))
            .flatMap(current -> scheduleRepository
                .save(ScheduleMapper.toEntity(id, schedule, current.createdAt(), now()))
                .then(targetRepository.deleteByScheduleId(id))
                .then(conditionRepository.deleteByScheduleId(id))
                .then(writeChildren(id, schedule))
                .thenReturn(id))
            .as(transactionalOperator::transactional)
            .doOnNext(replaced -> log.info("Schedule {} ({}) replaced", id, schedule.name()))
            .flatMap(this::get);
    }

    //the targets and the conditions go with it (ON DELETE CASCADE)
    public Mono<Void> remove(final long id) {
        return existing(id)
            .flatMap(current -> scheduleRepository.deleteById(id).thenReturn(current))
            .as(transactionalOperator::transactional)
            .doOnNext(removed -> log.info("Schedule {} ({}) removed", id, removed.name()))
            .then();
    }

    public Mono<Schedule> enable(final long id) {
        return switchTo(id, true);
    }

    public Mono<Schedule> disable(final long id) {
        return switchTo(id, false);
    }

    //a schedule already in that state is not written, so its updatedAt stays
    private Mono<Schedule> switchTo(final long id, final boolean enabled) {
        return existing(id)
            .flatMap(current -> current.enabled() == enabled
                ? Mono.just(current)
                : scheduleRepository.save(current.withEnabled(enabled, now()))
                    .doOnNext(saved -> log.info("Schedule {} ({}) {}", id, saved.name(),
                        enabled ? "enabled" : "disabled")))
            .flatMap(this::withChildren);
    }

    private Mono<ScheduleEntity> existing(final long id) {
        return scheduleRepository.findById(id)
            .switchIfEmpty(Mono.error(() -> BlindsException.scheduleNotFound(id)));
    }

    private Mono<Schedule> withChildren(final ScheduleEntity schedule) {
        return targetRepository.findByScheduleIdOrderById(schedule.id()).collectList()
            .flatMap(targets -> conditionRepository.findByScheduleIdOrderById(schedule.id()).collectList()
                .map(conditions -> ScheduleMapper.toSchedule(schedule, targets, conditions)));
    }

    //one after the other, in the order given - the ids keep it
    private Mono<Void> writeChildren(final long id, final Schedule schedule) {
        return Flux.fromIterable(ScheduleMapper.toTargets(id, schedule))
            .concatMap(targetRepository::save)
            .thenMany(Flux.fromIterable(ScheduleMapper.toConditions(id, schedule)).concatMap(conditionRepository::save))
            .then();
    }

    //cut to the microseconds the column keeps, so that the answer of a write reads as a later read
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
