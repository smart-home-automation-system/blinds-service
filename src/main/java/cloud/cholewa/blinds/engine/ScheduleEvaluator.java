package cloud.cholewa.blinds.engine;

import cloud.cholewa.blinds.engine.WeatherNow.Observation;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.WindowState;
import cloud.cholewa.blinds.model.schedule.ConditionMetric;
import cloud.cholewa.blinds.model.schedule.ScheduleCondition;
import cloud.cholewa.blinds.model.schedule.ScheduleDayType;
import cloud.cholewa.blinds.model.schedule.ScheduleTarget;
import cloud.cholewa.blinds.model.schedule.ScheduleWindow;
import cloud.cholewa.blinds.model.schedule.WindowEnd;
import cloud.cholewa.blinds.model.schedule.WindowKind;
import cloud.cholewa.home.model.DayType;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

//Every rule of the engine, without I/O: what the window of a schedule is today, whether today is
//one of its days, what each condition says now, and what the schedule does in this minute. The
//engine gathers the inputs and acts; this decides. Pinned by ScheduleEvaluatorTest.
//What is not known never holds: a metric the station did not send, one older than WEATHER_MAX_AGE,
//a time of the sun the day does not have - the schedule that needs it does not fire, the others do
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ScheduleEvaluator {

    static final Duration WEATHER_MAX_AGE = Duration.ofMinutes(10);
    private static final int LAST_MINUTE_OF_THE_DAY = 24 * 60 - 1;

    /** What a condition says in this minute, before its hold is counted. */
    public enum Truth { TRUE, FALSE, UNKNOWN }

    /**
     * A condition with its hold: {@code met} - true for its whole hold; {@code holding} - true, the
     * hold not over yet; {@code not met} - false; {@code unknown} - its input is not known;
     * {@code per blind} - a window closed condition, whose state is that of each target.
     */
    public enum ConditionStatus {
        MET("met"), HOLDING("holding"), NOT_MET("not met"), UNKNOWN("unknown"),
        //a window closed condition is judged for every blind of the targets, not for the schedule
        PER_BLIND("per blind");

        private final String value;

        ConditionStatus(final String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }

    /** What a schedule does in this minute, the first that applies, in this order. */
    public enum Verdict {
        DISABLED("disabled"),
        NOT_TODAY("not today"),
        NO_WINDOW("no window today"),
        FIRED("fired today"),
        BEFORE_WINDOW("before the window"),
        AFTER_WINDOW("after the window"),
        NO_BLINDS("no blind of its targets is known"),
        WAITING("waiting for the conditions"),
        FIRE("fires");

        private final String value;

        Verdict(final String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }

    /**
     * The window of a schedule on one day, local time; {@code from} included, {@code to} not. An end
     * before the start is the next day (HAS-290); an end at the start is no window.
     */
    public record ResolvedWindow(LocalTime from, LocalTime to) {

        public boolean isEmpty() {
            return to.equals(from);
        }

        public boolean crossesMidnight() {
            return to.isBefore(from);
        }
    }

    /**
     * The window of a schedule as it opens on {@code date} - the day it counts for: its day type, its
     * once a day ({@code fired_on}) and the sun of its start. {@code overnight} - it ends the next day.
     */
    public record Occurrence(LocalDate date, LocalTime from, LocalTime to, boolean overnight) {

        public LocalDateTime start() {
            return date.atTime(from);
        }

        public LocalDateTime end() {
            return (overnight ? date.plusDays(1) : date).atTime(to);
        }

        public boolean isEmpty() {
            return !overnight && !to.isAfter(from);
        }

        public boolean contains(final LocalDateTime time) {
            return !isEmpty() && !time.isBefore(start()) && time.isBefore(end());
        }
    }

    //empty when an end is a time of the sun the day does not have
    public static Optional<ResolvedWindow> window(final ScheduleWindow window, final SunDay sunDay) {
        final LocalTime from = end(window.from(), sunDay);
        final LocalTime to = end(window.to(), sunDay);
        return from == null || to == null ? Optional.empty() : Optional.of(new ResolvedWindow(from, to));
    }

    //The window that opens on date: the start by the sun of that day, the end by the sun of the day it
    //falls on - an end before the start is the next day's (a window of 21:00 to 06:00, or of the
    //sunset to the sunrise). Empty when an end is a time of the sun its day does not have
    public static Optional<Occurrence> occurrence(
        final ScheduleWindow window,
        final LocalDate date,
        final SunDay startDay,
        final SunDay nextDay
    ) {
        final LocalTime from = end(window.from(), startDay);
        if (from == null) {
            return Optional.empty();
        }
        final LocalTime to = end(window.to(), startDay);
        if (to != null && !to.isBefore(from)) {
            return Optional.of(new Occurrence(date, from, to, false));
        }
        final LocalTime next = end(window.to(), nextDay);
        if (next == null || to == null && !next.isBefore(from)) {
            //no end, or one only the next day has and that is no night
            return Optional.empty();
        }
        return Optional.of(new Occurrence(date, from, next, true));
    }

    //Which window counts now: the one that opened the day before while it lasts into today - on a
    //day it runs on - otherwise today's, which may be empty
    public static Optional<Occurrence> current(
        final Optional<Occurrence> previous,
        final boolean previousRuns,
        final Optional<Occurrence> today,
        final LocalDateTime now
    ) {
        return previous.filter(night -> previousRuns && night.overnight() && night.contains(now))
            .or(() -> today);
    }

    //A time of the sun moved by its offset stays within the day - an evening past midnight is the
    //last minute of the day, not the small hours of it - and is then held between earliest and latest
    static LocalTime end(final WindowEnd end, final SunDay sunDay) {
        if (end.kind() == WindowKind.CLOCK) {
            return end.time();
        }

        final LocalTime base = end.kind() == WindowKind.SUNRISE ? sunDay.sunrise() : sunDay.sunset();
        if (base == null) {
            return null;
        }

        final int minute = Math.clamp(base.toSecondOfDay() / 60 + (long) end.offsetMinutes(), 0, LAST_MINUTE_OF_THE_DAY);
        LocalTime time = LocalTime.ofSecondOfDay(minute * 60L);
        if (end.earliest() != null && time.isBefore(end.earliest())) {
            time = end.earliest();
        }
        if (end.latest() != null && time.isAfter(end.latest())) {
            time = end.latest();
        }
        return time;
    }

    public static boolean runsOn(final ScheduleDayType scheduleDays, final DayType today) {
        return switch (scheduleDays) {
            case ANY -> true;
            case WORKDAY -> today == DayType.WORKDAY;
            case DAY_OFF -> today == DayType.DAY_OFF;
        };
    }

    //a condition of the weather; window closed is not known without the blind it is about
    public static Truth truth(final ScheduleCondition condition, final WeatherNow weather, final LocalDateTime now) {
        return truth(condition, weather, null, now);
    }

    //Every metric named: a metric added has to be decided here, or the compiler stops. Window closed is
    //the window of one blind (HAS-290): one without a sensor is closed, one whose sensor is not known
    //is unknown - and a schedule that asks for it does not fire for that blind
    public static Truth truth(
        final ScheduleCondition condition,
        final WeatherNow weather,
        final WindowState window,
        final LocalDateTime now
    ) {
        return switch (condition.metric()) {
            case ILLUMINANCE, OUTSIDE_TEMPERATURE, WIND_GUST -> compared(condition, fresh(weather, condition, now));
            case RAINING -> flag(condition, fresh(weather, condition, now));
            case WINDOW_CLOSED -> {
                final Boolean closed = window == null ? null : window.closed();
                yield closed == null || condition.value() == null ? Truth.UNKNOWN : of(closed.equals(condition.value()));
            }
        };
    }

    //whether the conditions of a schedule are judged for each blind of its targets
    public static boolean perBlind(final List<ScheduleCondition> conditions) {
        return conditions.stream().anyMatch(ScheduleEvaluator::aboutTheWindow);
    }

    public static boolean aboutTheWindow(final ScheduleCondition condition) {
        return condition.metric() == ConditionMetric.WINDOW_CLOSED;
    }

    public static boolean ready(final List<ConditionStatus> conditions) {
        return conditions.stream().allMatch(status -> status == ConditionStatus.MET);
    }

    //the observation when it is recent enough to stand for now; a value the station still had a
    //moment ago is confirmed by every packet, so an old measuredAt means the station fell silent
    static Observation fresh(final WeatherNow weather, final ScheduleCondition condition, final LocalDateTime now) {
        final Observation observation = weather.metrics().get(condition.metric());
        if (observation == null || observation.stale() || observation.measuredAt().isBefore(now.minus(WEATHER_MAX_AGE))) {
            return null;
        }
        return observation;
    }

    private static Truth compared(final ScheduleCondition condition, final Observation observation) {
        final BigDecimal value = observation == null ? null : number(observation.value());
        if (value == null) {
            return Truth.UNKNOWN;
        }

        final int comparison = value.compareTo(condition.threshold());
        return switch (condition.operator()) {
            case LT -> of(comparison < 0);
            case GT -> of(comparison > 0);
            case IS -> Truth.UNKNOWN;
        };
    }

    private static Truth flag(final ScheduleCondition condition, final Observation observation) {
        if (observation == null || !(observation.value() instanceof Boolean value)) {
            return Truth.UNKNOWN;
        }
        return of(value.equals(condition.value()));
    }

    //by its text, so that 0.1 stays 0.1 and is not the double nearest to it
    private static BigDecimal number(final Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException e) {
            //NaN or an infinity
            return null;
        }
    }

    private static Truth of(final boolean value) {
        return value ? Truth.TRUE : Truth.FALSE;
    }

    //trueSince is when the condition was first seen true without a break, null when it was not seen
    //yet - then it counts from now, and a hold of 0 is met at once
    public static ConditionStatus status(
        final ScheduleCondition condition,
        final Truth truth,
        final LocalDateTime trueSince,
        final LocalDateTime now
    ) {
        return switch (truth) {
            case UNKNOWN -> ConditionStatus.UNKNOWN;
            case FALSE -> ConditionStatus.NOT_MET;
            case TRUE -> {
                final LocalDateTime since = trueSince == null ? now : trueSince;
                yield now.isBefore(since.plusMinutes(condition.holdMinutes())) ? ConditionStatus.HOLDING : ConditionStatus.MET;
            }
        };
    }

    /**
     * What a schedule does now.<br>
     * {@code runsOnItsDay} - whether the day its window opened on is one of its days;
     * {@code blinds} - how many blinds of its targets the registry knows;
     * {@code pending} - how many of them it did not move in this window yet;
     * {@code ready} - how many of those have every condition met.
     */
    public static Verdict decide(
        final boolean enabled,
        final boolean runsOnItsDay,
        final Optional<Occurrence> window,
        final LocalDateTime now,
        final int blinds,
        final int pending,
        final int ready
    ) {
        if (!enabled) {
            return Verdict.DISABLED;
        }
        if (!runsOnItsDay) {
            return Verdict.NOT_TODAY;
        }
        if (window.isEmpty() || window.get().isEmpty()) {
            return Verdict.NO_WINDOW;
        }
        if (blinds > 0 && pending == 0) {
            return Verdict.FIRED;
        }
        if (now.isBefore(window.get().start())) {
            return Verdict.BEFORE_WINDOW;
        }
        if (!now.isBefore(window.get().end())) {
            return Verdict.AFTER_WINDOW;
        }
        if (blinds == 0) {
            return Verdict.NO_BLINDS;
        }
        if (ready == 0) {
            return Verdict.WAITING;
        }
        return Verdict.FIRE;
    }

    //The blinds of the targets in the order of the targets, each once - a blind named on its own and
    //through its room is moved once. A target the registry does not know moves nothing
    public static List<Blind> blindsOf(final List<ScheduleTarget> targets, final List<Blind> known) {
        final Map<Long, Blind> blinds = new LinkedHashMap<>();
        for (final ScheduleTarget target : targets) {
            known.stream()
                .filter(blind -> target.room() != null ? blind.room() == target.room() : target.deviceId().equals(blind.id()))
                .forEach(blind -> blinds.putIfAbsent(blind.id(), blind));
        }
        return List.copyOf(blinds.values());
    }
}
