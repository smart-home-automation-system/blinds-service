package cloud.cholewa.blinds.engine;

import cloud.cholewa.blinds.database.model.ScheduleRunEntity;
import cloud.cholewa.blinds.database.repository.ScheduleRunRepository;
import cloud.cholewa.blinds.engine.DayTypes.TodayType;
import cloud.cholewa.blinds.engine.HoldTracker.Key;
import cloud.cholewa.blinds.engine.ScheduleEvaluator.ConditionStatus;
import cloud.cholewa.blinds.engine.ScheduleEvaluator.Occurrence;
import cloud.cholewa.blinds.engine.ScheduleEvaluator.Truth;
import cloud.cholewa.blinds.engine.ScheduleEvaluator.Verdict;
import cloud.cholewa.blinds.engine.WeatherNow.Observation;
import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindCommand;
import cloud.cholewa.blinds.model.CommandSource;
import cloud.cholewa.blinds.model.WindowState;
import cloud.cholewa.blinds.model.schedule.Schedule;
import cloud.cholewa.blinds.model.schedule.ScheduleCondition;
import cloud.cholewa.blinds.model.schedule.ScheduleToday;
import cloud.cholewa.blinds.registry.BlindRegistry;
import cloud.cholewa.blinds.service.BlindsCommandService;
import cloud.cholewa.blinds.service.schedule.SchedulePauseService;
import cloud.cholewa.blinds.service.schedule.ScheduleService;
import cloud.cholewa.blinds.window.WindowSensors;
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
//day type, the sun, the weather, the windows of its blinds and the holds of its conditions, and a
//schedule that fires moves the blinds of its targets it has not moved in this window yet, one after
//the other, with the source schedule.
//- A pass never fails: an input that cannot be read is unknown, which keeps the schedules that need
//  it from firing and nothing else; a pass that fails all the same (the runs cannot be read - then
//  nothing may fire) is a WARN, and the next minute tries again. Reading the inputs is bounded by
//  PASS_TIMEOUT; the commands are never cut off - one cut off halfway may have moved its blind with no
//  run stored - each is bounded by the timeout of its client. fixedDelay waits.
//- A window may run past midnight (HAS-290): it belongs to the day it opened on - its day type, its
//  sun, its once a day (fired_on). While the window that opened yesterday lasts, it is the one judged.
//- Once a window per schedule and blind: the runs are read before anything fires, and a blind is
//  remembered in memory before its command is sent - the run row is written after it, so a failed
//  write does not make the engine move the blind again; a restart reads the rows.
//- Window closed is judged for each blind, by the sensors of its own window, with a hold of its own
//  (HAS-290); the other conditions are the same for every blind and are judged once.
//- A command is sent once and never repeated: a blind that failed is a run (FAILED) all the same,
//  since it may have moved. A blind the protection holds back is not a run - it is tried again in
//  the next minute of the window.
//- A paused blind (HAS-232) is not moved: the run that would have moved it is stored as PAUSED - its
//  window is used up as by any run - and the pause ends with it. Every schedule that would move the
//  blind in the same pass is held back too, so two schedules of one minute do not undo the pause.
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
    private final WindowSensors windowSensors;
    private final SchedulePauseService schedulePauseService;
    private final Clock clock;

    private final AtomicReference<WeatherNow> weather = new AtomicReference<>(WeatherNow.unknown());
    private final AtomicBoolean weatherFailing = new AtomicBoolean(false);
    private final Map<Long, String> lastDecisions = new ConcurrentHashMap<>();
    private final Set<RunKey> sent = ConcurrentHashMap.newKeySet();

    //a blind moved by a schedule in the window that opened on date
    private record RunKey(long scheduleId, long deviceId, LocalDate date) {
    }

    private record RunDay(long scheduleId, LocalDate date) {
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
        final LocalDate yesterday = today.minusDays(1);
        sent.removeIf(key -> key.date().isBefore(yesterday));

        return Mono.zip(dayTypes.of(yesterday), dayTypes.of(today), readWeather(now),
                runRepository.findByFiredOnIn(List.of(yesterday, today)).collectList(),
                //without the pauses nothing may fire: a paused blind would be moved
                schedulePauseService.paused())
            .timeout(PASS_TIMEOUT)
            //nothing is judged without the runs: a minute not seen must not count towards a hold
            .doOnError(e -> holdTracker.retain(Set.of()))
            .flatMap(inputs -> {
                final Days days = days(now, inputs.getT1(), inputs.getT2(), inputs.getT3());
                final Map<RunDay, Set<Long>> fired = fired(inputs.getT4());
                final Set<Key> keys = new HashSet<>();
                final Set<Long> paused = inputs.getT5().keySet();

                return Flux.fromIterable(schedules)
                    .concatMap(schedule -> judge(schedule, days, fired, keys, paused))
                    .then(Mono.fromRunnable(() -> {
                        holdTracker.retain(keys);
                        final Set<Long> ids = schedules.stream().map(Schedule::id).collect(Collectors.toSet());
                        lastDecisions.keySet().retainAll(ids);
                    }));
            });
    }

    private Days days(final LocalDateTime now, final TodayType yesterday, final TodayType today, final WeatherNow weatherNow) {
        final LocalDate date = now.toLocalDate();
        return new Days(now, yesterday, today, sunCalendar.of(date.minusDays(1)), sunCalendar.of(date),
            sunCalendar.of(date.plusDays(1)), weatherNow);
    }

    //the runs of the rows and those sent in this instance, which a failed write may have left out
    private Map<RunDay, Set<Long>> fired(final List<ScheduleRunEntity> runs) {
        final Map<RunDay, Set<Long>> fired = new HashMap<>();
        runs.forEach(run -> fired.computeIfAbsent(new RunDay(run.scheduleId(), run.firedOn()), ignored -> new HashSet<>())
            .add(run.deviceId()));
        sent.forEach(key -> fired.computeIfAbsent(new RunDay(key.scheduleId(), key.date()), ignored -> new HashSet<>())
            .add(key.deviceId()));
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

    //the window that counts now: the night that opened yesterday while it lasts, otherwise today's
    private static Chosen chosen(final Schedule schedule, final Days days) {
        final LocalDate today = days.now().toLocalDate();
        final Optional<Occurrence> previous = ScheduleEvaluator.occurrence(schedule.window(), today.minusDays(1),
            days.sunYesterday(), days.sunToday());
        final boolean previousRuns = ScheduleEvaluator.runsOn(schedule.dayType(), days.yesterday().type());
        final Optional<Occurrence> todays = ScheduleEvaluator.occurrence(schedule.window(), today,
            days.sunToday(), days.sunTomorrow());
        final Optional<Occurrence> current = ScheduleEvaluator.current(previous, previousRuns, todays, days.now());

        final boolean fromYesterday = current.filter(occurrence -> occurrence.date().isBefore(today)).isPresent();
        return fromYesterday
            ? new Chosen(today.minusDays(1), days.yesterday(), current)
            : new Chosen(today, days.today(), current);
    }

    private Mono<Void> judge(
        final Schedule schedule,
        final Days days,
        final Map<RunDay, Set<Long>> fired,
        final Set<Key> keys,
        final Set<Long> paused
    ) {
        final LocalDateTime now = days.now();
        final Chosen chosen = chosen(schedule, days);
        final Set<Long> firedIds = fired.getOrDefault(new RunDay(schedule.id(), chosen.date()), Set.of());

        final List<Blind> blinds = ScheduleEvaluator.blindsOf(schedule.targets(), blindRegistry.current());
        final List<Blind> pending = blinds.stream().filter(blind -> !firedIds.contains(blind.id())).toList();

        //the conditions every blind shares, judged once; window closed stands in as per blind
        final List<ConditionStatus> shared = new ArrayList<>();
        for (final ScheduleCondition condition : schedule.conditions()) {
            if (ScheduleEvaluator.aboutTheWindow(condition)) {
                shared.add(ConditionStatus.PER_BLIND);
                continue;
            }
            final Key key = new Key(schedule.id(), condition);
            keys.add(key);
            final Truth truth = ScheduleEvaluator.truth(condition, days.weather(), now);
            shared.add(ScheduleEvaluator.status(condition, truth, holdTracker.observe(key, truth, now), now));
        }

        //every blind of the targets is watched, so that a hold of its window counts before its turn
        final Map<Long, WindowState> windows = new HashMap<>();
        final List<Blind> ready = new ArrayList<>();
        for (final Blind blind : blinds) {
            final List<ConditionStatus> statuses = new ArrayList<>(shared);
            if (ScheduleEvaluator.perBlind(schedule.conditions())) {
                final WindowState window = windowSensors.stateOf(blind, now);
                windows.put(blind.id(), window);
                for (int i = 0; i < schedule.conditions().size(); i++) {
                    final ScheduleCondition condition = schedule.conditions().get(i);
                    if (ScheduleEvaluator.aboutTheWindow(condition)) {
                        final Key key = new Key(schedule.id(), condition, blind.id());
                        keys.add(key);
                        final Truth truth = ScheduleEvaluator.truth(condition, days.weather(), window, now);
                        statuses.set(i, ScheduleEvaluator.status(condition, truth, holdTracker.observe(key, truth, now), now));
                    }
                }
            }
            if (pending.contains(blind) && ScheduleEvaluator.ready(statuses)) {
                ready.add(blind);
            }
        }

        final Verdict verdict = ScheduleEvaluator.decide(
            schedule.enabled(),
            ScheduleEvaluator.runsOn(schedule.dayType(), chosen.type().type()),
            chosen.occurrence(),
            now,
            blinds.size(),
            pending.size(),
            ready.size());

        logDecision(schedule, verdict, chosen, shared, windows, ready.size(), pending.size());

        return verdict == Verdict.FIRE
            ? Flux.fromIterable(ready)
                .concatMap(blind -> paused.contains(blind.id())
                    ? pausedRun(schedule, blind, chosen.date(), now)
                    : fire(schedule, blind, chosen.date(), now))
                .then()
            : Mono.empty();
    }

    private void logDecision(
        final Schedule schedule,
        final Verdict verdict,
        final Chosen chosen,
        final List<ConditionStatus> statuses,
        final Map<Long, WindowState> windows,
        final int ready,
        final int pending
    ) {
        final StringBuilder reason = new StringBuilder(verdict.value());
        chosen.occurrence().ifPresent(window -> reason.append(", window ").append(window.from()).append('-')
            .append(window.to()).append(window.overnight() ? " (next day)" : "").append(" of ").append(chosen.date()));
        for (int i = 0; i < statuses.size(); i++) {
            final ScheduleCondition condition = schedule.conditions().get(i);
            reason.append(i == 0 ? ": " : ", ")
                .append(describe(condition))
                .append(' ')
                .append(statuses.get(i).value());
        }
        if (!windows.isEmpty()) {
            reason.append("; windows ").append(windows.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + " " + entry.getValue().value())
                    .collect(Collectors.joining(", ")))
                .append("; ready ").append(ready).append(" of ").append(pending);
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

    private Mono<Void> fire(final Schedule schedule, final Blind blind, final LocalDate windowDate, final LocalDateTime now) {
        final RunKey key = new RunKey(schedule.id(), blind.id(), windowDate);
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

    //Nothing is sent; the run is stored as any run, so the window is used up, and the pause ends. A
    //failed write is remembered in this instance as a sent run is; the pause is deleted all the same -
    //otherwise it would hold back the next window too
    private Mono<Void> pausedRun(final Schedule schedule, final Blind blind, final LocalDate windowDate, final LocalDateTime now) {
        final RunKey key = new RunKey(schedule.id(), blind.id(), windowDate);
        sent.add(key);
        log.info("Schedule {} ({}) did not move blind {} ({}, {}): its schedules are paused, the pause ends with this run",
            schedule.id(), schedule.name(), blind.id(), blind.name(), blind.room().getValue());

        return runRepository.save(new ScheduleRunEntity(null, schedule.id(), blind.id(), windowDate, now, RunOutcome.PAUSED))
            .doOnError(e -> log.error("The paused run of schedule {} for blind {} was not stored, it is remembered "
                + "until a restart: {}", schedule.id(), blind.id(), e.toString()))
            .onErrorComplete()
            .then(schedulePauseService.end(blind.id()))
            .onErrorResume(e -> {
                log.error("The pause of blind {} could not be ended, it holds back the next run too: {}", blind.id(), e.toString());
                return Mono.empty();
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

    //A schedule now, for the read: the weather as the last pass read it, the holds and the windows as
    //it left them, the day types as the engine keeps them; nothing the engine remembers is changed
    public Mono<ScheduleToday> today(final Schedule schedule) {
        return Mono.defer(() -> {
            final LocalDateTime now = now();
            final LocalDate today = now.toLocalDate();
            return Mono.zip(dayTypes.of(today.minusDays(1)), dayTypes.of(today))
                .flatMap(types -> {
                    final Days days = days(now, types.getT1(), types.getT2(), weather.get());
                    final Chosen chosen = chosen(schedule, days);
                    return runRepository.findByScheduleIdAndFiredOnOrderById(schedule.id(), chosen.date()).collectList()
                        .map(runs -> today(schedule, days, chosen, runs));
                });
        });
    }

    private ScheduleToday today(final Schedule schedule, final Days days, final Chosen chosen, final List<ScheduleRunEntity> runs) {
        final LocalDateTime now = days.now();
        final List<ConditionStatus> shared = new ArrayList<>();
        final List<ScheduleToday.Condition> conditions = new ArrayList<>();
        for (final ScheduleCondition condition : schedule.conditions()) {
            if (ScheduleEvaluator.aboutTheWindow(condition)) {
                shared.add(ConditionStatus.PER_BLIND);
                conditions.add(new ScheduleToday.Condition(condition.metric(), condition.operator(), condition.threshold(),
                    condition.value(), condition.holdMinutes(), null, null, null, ConditionStatus.PER_BLIND));
                continue;
            }
            final Truth truth = ScheduleEvaluator.truth(condition, days.weather(), now);
            final LocalDateTime since = truth == Truth.TRUE ? holdTracker.since(new Key(schedule.id(), condition)) : null;
            final ConditionStatus status = ScheduleEvaluator.status(condition, truth, since, now);
            final Observation observation = ScheduleEvaluator.fresh(days.weather(), condition, now);
            shared.add(status);
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
            .filter(key -> key.scheduleId() == schedule.id() && key.date().equals(chosen.date()))
            .forEach(key -> fired.add(key.deviceId()));

        final boolean perBlind = ScheduleEvaluator.perBlind(schedule.conditions());
        final List<Blind> blinds = ScheduleEvaluator.blindsOf(schedule.targets(), blindRegistry.current());
        final List<ScheduleToday.Target> targets = new ArrayList<>();
        int pending = 0;
        int ready = 0;
        for (final Blind blind : blinds) {
            final WindowState window = windowSensors.stateOf(blind, now);
            final List<ConditionStatus> statuses = new ArrayList<>(shared);
            final List<ConditionStatus> ofTheWindow = new ArrayList<>();
            for (int i = 0; i < schedule.conditions().size(); i++) {
                final ScheduleCondition condition = schedule.conditions().get(i);
                if (ScheduleEvaluator.aboutTheWindow(condition)) {
                    final Truth truth = ScheduleEvaluator.truth(condition, days.weather(), window, now);
                    final LocalDateTime since = truth == Truth.TRUE
                        ? holdTracker.since(new Key(schedule.id(), condition, blind.id()))
                        : null;
                    final ConditionStatus status = ScheduleEvaluator.status(condition, truth, since, now);
                    statuses.set(i, status);
                    ofTheWindow.add(status);
                }
            }
            if (!fired.contains(blind.id())) {
                pending++;
                if (ScheduleEvaluator.ready(statuses)) {
                    ready++;
                }
            }

            final ScheduleRunEntity run = runOf.remove(blind.id());
            targets.add(new ScheduleToday.Target(blind.id(), blind.name(), blind.room().getValue(), window,
                perBlind ? together(ofTheWindow) : null,
                run == null ? null : run.firedAt(), run == null ? null : run.outcome()));
        }
        runOf.values().forEach(run -> targets.add(
            new ScheduleToday.Target(run.deviceId(), null, null, null, null, run.firedAt(), run.outcome())));

        final boolean runsOnItsDay = ScheduleEvaluator.runsOn(schedule.dayType(), chosen.type().type());

        return new ScheduleToday(
            schedule.id(),
            chosen.date(),
            chosen.type().type().getValue(),
            chosen.type().fromCalendar(),
            runsOnItsDay,
            chosen.occurrence()
                .map(window -> new ScheduleToday.Window(window.from(), window.to(), window.overnight() ? Boolean.TRUE : null))
                .orElse(null),
            conditions,
            targets,
            ScheduleEvaluator.decide(schedule.enabled(), runsOnItsDay, chosen.occurrence(), now, blinds.size(), pending, ready));
    }

    //the window closed conditions of one blind as one: met only when every one is, otherwise the
    //farthest from it - unknown, then not met, then holding
    private static ConditionStatus together(final List<ConditionStatus> statuses) {
        for (final ConditionStatus worst : List.of(ConditionStatus.UNKNOWN, ConditionStatus.NOT_MET, ConditionStatus.HOLDING)) {
            if (statuses.contains(worst)) {
                return worst;
            }
        }
        return ConditionStatus.MET;
    }

    //to the second: the column and the log need no more
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private record Days(
        LocalDateTime now,
        TodayType yesterday,
        TodayType today,
        SunDay sunYesterday,
        SunDay sunToday,
        SunDay sunTomorrow,
        WeatherNow weather
    ) {
    }

    //the window that counts now, the day it opened on and the type of that day
    private record Chosen(LocalDate date, TodayType type, Optional<Occurrence> occurrence) {
    }
}
