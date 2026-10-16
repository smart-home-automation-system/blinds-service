package cloud.cholewa.blinds.engine;

import cloud.cholewa.blinds.database.model.ScheduleRunEntity;
import cloud.cholewa.blinds.database.repository.ScheduleRunRepository;
import cloud.cholewa.blinds.engine.DayTypes.TodayType;
import cloud.cholewa.blinds.engine.HoldTracker.Key;
import cloud.cholewa.blinds.engine.ScheduleEvaluator.ConditionStatus;
import cloud.cholewa.blinds.engine.ScheduleEvaluator.ResolvedWindow;
import cloud.cholewa.blinds.engine.ScheduleEvaluator.Truth;
import cloud.cholewa.blinds.engine.ScheduleEvaluator.Verdict;
import cloud.cholewa.blinds.engine.WeatherNow.Observation;
import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindCommand;
import cloud.cholewa.blinds.model.CommandSource;
import cloud.cholewa.blinds.model.schedule.Schedule;
import cloud.cholewa.blinds.model.schedule.ScheduleCondition;
import cloud.cholewa.blinds.model.schedule.ScheduleToday;
import cloud.cholewa.blinds.registry.BlindRegistry;
import cloud.cholewa.blinds.service.BlindsCommandService;
import cloud.cholewa.blinds.service.schedule.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

//The schedule engine (HAS-247): once a minute every schedule is judged by ScheduleEvaluator from the
//day type, the sun, the weather and the holds of its conditions, and a schedule that fires moves
//the blinds of its targets it has not moved today, one after the other, with the source schedule.
//- A pass never fails: an input that cannot be read is unknown, which keeps the schedules that need
//  it from firing and nothing else; a pass that fails all the same (the runs of the day cannot be
//  read - then nothing may fire) is a WARN, and the next minute tries again. Reading the inputs is
//  bounded by PASS_TIMEOUT; the commands are never cut off - one cut off halfway may have moved its
//  blind with no run stored - each is bounded by the timeout of its client. fixedDelay waits.
//- Once a day per schedule and blind: the runs of the day are read before anything fires, and a
//  blind is remembered in memory before its command is sent - the run row is written after it, so a
//  failed write does not make the engine move the blind again; a restart reads the rows.
//- A command is sent once and never repeated: a blind that failed is a run (FAILED) all the same,
//  since it may have moved. A blind the protection holds back is not a run - it is tried again in
//  the next minute of the window.
//- A decision is logged at INFO when it changes, with its reason, not every minute
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleEngine {

    static final Duration PASS_TIMEOUT = Duration.ofSeconds(50);

    private final ScheduleService scheduleService;
    private final ScheduleRunRepository runRepository;
    private final BlindRegistry blindRegistry;
    private final BlindsCommandService commandService;
    private final DayTypes dayTypes;
    private final SunCalendar sunCalendar;
    private final WeatherClient weatherClient;
    private final HoldTracker holdTracker;
    private final Clock clock;

    private final AtomicReference<WeatherNow> weather = new AtomicReference<>(WeatherNow.unknown());
    private final AtomicBoolean weatherFailing = new AtomicBoolean(false);
    private final Map<Long, String> lastDecisions = new ConcurrentHashMap<>();
    private final Set<RunKey> sent = ConcurrentHashMap.newKeySet();

    private record RunKey(long scheduleId, long deviceId, LocalDate date) {
    }

    //everything that depends on the time inside the defer: Spring subscribes to the same Mono every minute
    public Mono<Void> pass() {
        return Mono.defer(() -> {
                final LocalDateTime now = now();
                return scheduleService.all()
                    .flatMap(schedules -> schedules.isEmpty() ? forgetAll() : pass(schedules, now));
            })
            .onErrorResume(e -> {
                log.warn("A pass of the schedule engine failed, nothing more fires in it: {}", e.toString());
                return Mono.empty();
            });
    }

    private Mono<Void> forgetAll() {
        return Mono.fromRunnable(() -> {
            holdTracker.retain(Set.of());
            lastDecisions.clear();
        });
    }

    private Mono<Void> pass(final List<Schedule> schedules, final LocalDateTime now) {
        final LocalDate today = now.toLocalDate();
        sent.removeIf(key -> !key.date().equals(today));

        return Mono.zip(dayTypes.of(today), readWeather(now), runRepository.findByFiredOn(today).collectList())
            .timeout(PASS_TIMEOUT)
            //nothing is judged without the runs: a minute not seen must not count towards a hold
            .doOnError(e -> holdTracker.retain(Set.of()))
            .flatMap(inputs -> {
                final Day day = new Day(now, inputs.getT1(), sunCalendar.of(today), inputs.getT2());
                final Map<Long, Set<Long>> fired = firedToday(inputs.getT3(), today);
                final Set<Key> keys = new HashSet<>();

                return Flux.fromIterable(schedules)
                    .concatMap(schedule -> judge(schedule, day, fired.getOrDefault(schedule.id(), Set.of()), keys))
                    .then(Mono.fromRunnable(() -> {
                        holdTracker.retain(keys);
                        final Set<Long> ids = schedules.stream().map(Schedule::id).collect(Collectors.toSet());
                        lastDecisions.keySet().retainAll(ids);
                    }));
            });
    }

    //the runs of the rows and those sent in this instance, which a failed write may have left out
    private Map<Long, Set<Long>> firedToday(final List<ScheduleRunEntity> runs, final LocalDate today) {
        final Map<Long, Set<Long>> fired = new HashMap<>();
        runs.forEach(run -> fired.computeIfAbsent(run.scheduleId(), ignored -> new HashSet<>()).add(run.deviceId()));
        sent.stream()
            .filter(key -> key.date().equals(today))
            .forEach(key -> fired.computeIfAbsent(key.scheduleId(), ignored -> new HashSet<>()).add(key.deviceId()));
        return fired;
    }

    //a failure is a WARN once and an INFO when the weather is back, not a WARN every minute
    private Mono<WeatherNow> readWeather(final LocalDateTime now) {
        return weatherClient.current()
            .map(metrics -> new WeatherNow(metrics, now))
            .doOnNext(read -> {
                if (weatherFailing.compareAndSet(true, false)) {
                    log.info("The weather is read again");
                }
            })
            .onErrorResume(e -> {
                if (weatherFailing.compareAndSet(false, true)) {
                    log.warn("The weather could not be read, every weather condition is unknown until it is: {}", e.toString());
                }
                return Mono.just(WeatherNow.unknown());
            })
            .doOnNext(weather::set);
    }

    private Mono<Void> judge(final Schedule schedule, final Day day, final Set<Long> fired, final Set<Key> keys) {
        final LocalDateTime now = day.now();
        final List<ConditionStatus> statuses = new ArrayList<>();
        for (final ScheduleCondition condition : schedule.conditions()) {
            final Key key = new Key(schedule.id(), condition);
            keys.add(key);
            final Truth truth = ScheduleEvaluator.truth(condition, day.weather(), now);
            statuses.add(ScheduleEvaluator.status(condition, truth, holdTracker.observe(key, truth, now), now));
        }

        final List<Blind> blinds = ScheduleEvaluator.blindsOf(schedule.targets(), blindRegistry.current());
        final List<Blind> pending = blinds.stream().filter(blind -> !fired.contains(blind.id())).toList();
        final Optional<ResolvedWindow> window = ScheduleEvaluator.window(schedule.window(), day.sun());
        final Verdict verdict = ScheduleEvaluator.decide(
            schedule.enabled(),
            ScheduleEvaluator.runsOn(schedule.dayType(), day.type().type()),
            window,
            now.toLocalTime(),
            statuses,
            blinds.size(),
            pending.size());

        logDecision(schedule, verdict, window, statuses);

        return verdict == Verdict.FIRE
            ? Flux.fromIterable(pending).concatMap(blind -> fire(schedule, blind, now)).then()
            : Mono.empty();
    }

    private void logDecision(
        final Schedule schedule,
        final Verdict verdict,
        final Optional<ResolvedWindow> window,
        final List<ConditionStatus> statuses
    ) {
        final StringBuilder reason = new StringBuilder(verdict.value());
        window.ifPresent(resolved -> reason.append(", window ").append(resolved.from()).append('-').append(resolved.to()));
        for (int i = 0; i < statuses.size(); i++) {
            final ScheduleCondition condition = schedule.conditions().get(i);
            reason.append(i == 0 ? ": " : ", ")
                .append(describe(condition))
                .append(' ')
                .append(statuses.get(i).value());
        }

        final String text = reason.toString();
        if (!text.equals(lastDecisions.put(schedule.id(), text))) {
            log.info("Schedule {} ({}): {}", schedule.id(), schedule.name(), text);
        }
    }

    private static String describe(final ScheduleCondition condition) {
        final String compared = condition.threshold() != null
            ? condition.threshold().toPlainString()
            : String.valueOf(condition.value());
        return condition.metric().value() + " " + condition.operator().value() + " " + compared
            + (condition.holdMinutes() > 0 ? " for " + condition.holdMinutes() + " min" : "");
    }

    private Mono<Void> fire(final Schedule schedule, final Blind blind, final LocalDateTime now) {
        final RunKey key = new RunKey(schedule.id(), blind.id(), now.toLocalDate());
        sent.add(key);

        return command(schedule, blind)
            .thenReturn(RunOutcome.MOVED)
            .onErrorResume(BlindsException.class, e -> switch (e.getError()) {
                //not a run: the protection lets it through later, or the blind left the registry
                case PROTECTION_ACTIVE, NOT_FOUND_BLINDS, REGISTRY_UNAVAILABLE -> Mono.<RunOutcome>fromRunnable(() -> {
                    sent.remove(key);
                    log.info("Schedule {} ({}) did not move blind {} ({}): {}",
                        schedule.id(), schedule.name(), blind.id(), blind.name(), e.getMessage());
                });
                case UNSUPPORTED_POSITION -> Mono.just(RunOutcome.UNSUPPORTED);
                default -> Mono.just(RunOutcome.FAILED);
            })
            //anything else may have moved the blind too, and must not stop the rest of the pass
            .onErrorResume(e -> !(e instanceof BlindsException), e -> {
                log.error("Schedule {} ({}) failed on blind {} ({})", schedule.id(), schedule.name(), blind.id(), blind.name(), e);
                return Mono.just(RunOutcome.FAILED);
            })
            .flatMap(outcome -> {
                log.info("Schedule {} ({}) sent blind {} ({}, {}) to {}: {}", schedule.id(), schedule.name(),
                    blind.id(), blind.name(), blind.room().getValue(), target(schedule), outcome.value());
                return runRepository.save(new ScheduleRunEntity(null, schedule.id(), blind.id(), key.date(), now, outcome))
                    .doOnError(e -> log.error("The run of schedule {} for blind {} was not stored, it is remembered "
                        + "until a restart: {}", schedule.id(), blind.id(), e.toString()))
                    .onErrorComplete()
                    .then();
            });
    }

    private Mono<Void> command(final Schedule schedule, final Blind blind) {
        return switch (schedule.action()) {
            case OPEN -> commandService.move(blind.id(), BlindCommand.OPEN, CommandSource.SCHEDULE);
            case CLOSE -> commandService.move(blind.id(), BlindCommand.CLOSE, CommandSource.SCHEDULE);
            case POSITION -> commandService.moveTo(blind.id(), schedule.position(), CommandSource.SCHEDULE);
        };
    }

    private static String target(final Schedule schedule) {
        return schedule.position() == null ? schedule.action().value() : "position " + schedule.position();
    }

    //A schedule today, for the read: the weather as the last pass read it, the holds as it left them,
    //the day type as the engine keeps it; nothing the engine remembers is changed by a read
    public Mono<ScheduleToday> today(final Schedule schedule) {
        return Mono.defer(() -> {
            final LocalDateTime now = now();
            final LocalDate today = now.toLocalDate();
            return dayTypes.of(today)
                .zipWith(runRepository.findByScheduleIdAndFiredOnOrderById(schedule.id(), today).collectList())
                .map(read -> today(schedule, now, read.getT1(), sunCalendar.of(today), weather.get(), read.getT2()));
        });
    }

    private ScheduleToday today(
        final Schedule schedule,
        final LocalDateTime now,
        final TodayType type,
        final SunDay sun,
        final WeatherNow weatherNow,
        final List<ScheduleRunEntity> runs
    ) {
        final List<ConditionStatus> statuses = new ArrayList<>();
        final List<ScheduleToday.Condition> conditions = new ArrayList<>();
        for (final ScheduleCondition condition : schedule.conditions()) {
            final Truth truth = ScheduleEvaluator.truth(condition, weatherNow, now);
            final LocalDateTime since = truth == Truth.TRUE ? holdTracker.since(new Key(schedule.id(), condition)) : null;
            final ConditionStatus status = ScheduleEvaluator.status(condition, truth, since, now);
            final Observation observation = ScheduleEvaluator.fresh(weatherNow, condition, now);
            statuses.add(status);
            conditions.add(new ScheduleToday.Condition(
                condition.metric(), condition.operator(), condition.threshold(), condition.value(), condition.holdMinutes(),
                observation == null ? null : observation.value(),
                observation == null ? null : observation.measuredAt(),
                since,
                status));
        }

        final Map<Long, ScheduleRunEntity> runOf = runs.stream()
            .collect(Collectors.toMap(ScheduleRunEntity::deviceId, run -> run, (first, second) -> first));
        final Set<Long> fired = new HashSet<>(runOf.keySet());
        sent.stream()
            .filter(key -> key.scheduleId() == schedule.id() && key.date().equals(now.toLocalDate()))
            .forEach(key -> fired.add(key.deviceId()));

        final List<Blind> blinds = ScheduleEvaluator.blindsOf(schedule.targets(), blindRegistry.current());
        final List<ScheduleToday.Target> targets = new ArrayList<>();
        blinds.forEach(blind -> {
            final ScheduleRunEntity run = runOf.remove(blind.id());
            targets.add(new ScheduleToday.Target(blind.id(), blind.name(), blind.room().getValue(),
                run == null ? null : run.firedAt(), run == null ? null : run.outcome()));
        });
        runOf.values().forEach(run -> targets.add(new ScheduleToday.Target(run.deviceId(), null, null, run.firedAt(), run.outcome())));

        final Optional<ResolvedWindow> window = ScheduleEvaluator.window(schedule.window(), sun);
        final boolean runsToday = ScheduleEvaluator.runsOn(schedule.dayType(), type.type());
        final int pending = (int) blinds.stream().filter(blind -> !fired.contains(blind.id())).count();

        return new ScheduleToday(
            schedule.id(),
            now.toLocalDate(),
            type.type().getValue(),
            type.fromCalendar(),
            runsToday,
            window.map(resolved -> new ScheduleToday.Window(resolved.from(), resolved.to())).orElse(null),
            conditions,
            targets,
            ScheduleEvaluator.decide(schedule.enabled(), runsToday, window, now.toLocalTime(), statuses, blinds.size(), pending));
    }

    //to the second: the column and the log need no more
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private record Day(LocalDateTime now, TodayType type, SunDay sun, WeatherNow weather) {
    }
}
