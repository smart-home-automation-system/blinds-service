package cloud.cholewa.blinds.engine;

import cloud.cholewa.blinds.engine.WeatherNow.Observation;
import cloud.cholewa.blinds.model.Blind;
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
     * hold not over yet; {@code not met} - false; {@code unknown} - its input is not known.
     */
    public enum ConditionStatus {
        MET("met"), HOLDING("holding"), NOT_MET("not met"), UNKNOWN("unknown");

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

    /** The window of a schedule on one day, local time; {@code from} included, {@code to} not. */
    public record ResolvedWindow(LocalTime from, LocalTime to) {

        //an end before or at the start: a window of times of the sun that the day turned around
        public boolean isEmpty() {
            return !to.isAfter(from);
        }

        public boolean isBefore(final LocalTime time) {
            return time.isBefore(from);
        }

        public boolean isOver(final LocalTime time) {
            return !time.isBefore(to);
        }
    }

    //empty when an end is a time of the sun the day does not have
    public static Optional<ResolvedWindow> window(final ScheduleWindow window, final SunDay sunDay) {
        final LocalTime from = end(window.from(), sunDay);
        final LocalTime to = end(window.to(), sunDay);
        return from == null || to == null ? Optional.empty() : Optional.of(new ResolvedWindow(from, to));
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

    //Every metric named: a metric added has to be decided here, or the compiler stops. The window of
    //the room has no sensor in the house yet - it is never known, and a schedule that asks for it
    //does not fire
    public static Truth truth(final ScheduleCondition condition, final WeatherNow weather, final LocalDateTime now) {
        return switch (condition.metric()) {
            case ILLUMINANCE, OUTSIDE_TEMPERATURE, WIND_GUST -> compared(condition, fresh(weather, condition, now));
            case RAINING -> flag(condition, fresh(weather, condition, now));
            case WINDOW_CLOSED -> Truth.UNKNOWN;
        };
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
     * {@code blinds} - how many blinds of its targets the registry knows;
     * {@code pending} - how many of them it did not move today yet.
     */
    public static Verdict decide(
        final boolean enabled,
        final boolean runsToday,
        final Optional<ResolvedWindow> window,
        final LocalTime now,
        final List<ConditionStatus> conditions,
        final int blinds,
        final int pending
    ) {
        if (!enabled) {
            return Verdict.DISABLED;
        }
        if (!runsToday) {
            return Verdict.NOT_TODAY;
        }
        if (window.isEmpty() || window.get().isEmpty()) {
            return Verdict.NO_WINDOW;
        }
        if (blinds > 0 && pending == 0) {
            return Verdict.FIRED;
        }
        if (window.get().isBefore(now)) {
            return Verdict.BEFORE_WINDOW;
        }
        if (window.get().isOver(now)) {
            return Verdict.AFTER_WINDOW;
        }
        if (blinds == 0) {
            return Verdict.NO_BLINDS;
        }
        if (!conditions.stream().allMatch(status -> status == ConditionStatus.MET)) {
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
