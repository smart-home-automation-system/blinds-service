package cloud.cholewa.blinds.service.schedule;

import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.schedule.ConditionMetric;
import cloud.cholewa.blinds.model.schedule.ConditionOperator;
import cloud.cholewa.blinds.model.schedule.Schedule;
import cloud.cholewa.blinds.model.schedule.ScheduleAction;
import cloud.cholewa.blinds.model.schedule.ScheduleCondition;
import cloud.cholewa.blinds.model.schedule.ScheduleTarget;
import cloud.cholewa.blinds.model.schedule.ScheduleWindow;
import cloud.cholewa.blinds.model.schedule.WindowEnd;
import cloud.cholewa.blinds.model.schedule.WindowKind;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

//What a schedule has to say, and how its fields go together, before it is stored. Nothing in a
//body has a default - a field left out is refused, not read as off or zero. The schema (V2) repeats
//what one row can say of it and would answer with the name of a constraint, where this answers in
///words, by the values of the API ("day off", "wind gust"). The first broken rule is the answer.
//A time is to the minute already when the body is read (HH:mm, WindowEnd)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ScheduleRules {

    static final int MIN_NAME_CHARACTERS = 3;
    static final int MAX_NAME_CHARACTERS = 60;
    static final int MAX_OFFSET_MINUTES = 180;
    static final int MAX_HOLD_MINUTES = 120;
    static final int MAX_TARGETS = 30;
    static final int MAX_CONDITIONS = 10;

    public static void check(final Schedule schedule) {
        checkName(schedule.name());
        require(schedule.enabled() != null, "enabled is missing");
        require(schedule.dayType() != null, "dayType is missing");
        checkWindow(schedule.window());
        checkTargets(schedule.targets());
        checkConditions(schedule.conditions());
        checkAction(schedule.action(), schedule.position());
    }

    //counted in characters, as the schema counts them (VARCHAR(60)), not in the units of a string
    private static void checkName(final String name) {
        require(name != null && !name.isBlank(), "name is missing");
        final int characters = name.codePointCount(0, name.length());
        require(characters >= MIN_NAME_CHARACTERS && characters <= MAX_NAME_CHARACTERS,
            "name has " + MIN_NAME_CHARACTERS + " to " + MAX_NAME_CHARACTERS + " characters");
        require(name.strip().equals(name), "name does not start or end with a space");
    }

    //A window of two clock times runs forward within one day: it does not cross midnight, and it
    //is not empty. Where an end is a time of the sun its order is known only on the day - the
    //engine resolves it then and lets a window that ends before it starts pass without firing
    private static void checkWindow(final ScheduleWindow window) {
        require(window != null, "window is missing");
        checkEnd("window.from", window.from());
        checkEnd("window.to", window.to());

        if (window.from().kind() == WindowKind.CLOCK && window.to().kind() == WindowKind.CLOCK) {
            require(window.to().time().isAfter(window.from().time()),
                "window.to is after window.from when both are clock times");
        }
    }

    private static void checkEnd(final String end, final WindowEnd value) {
        require(value != null, end + " is missing");
        require(value.kind() != null, end + ".kind is missing");

        if (value.kind() == WindowKind.CLOCK) {
            require(value.time() != null, end + ".time is missing for a clock time");
            require(value.offsetMinutes() == null && value.earliest() == null && value.latest() == null,
                end + " is a clock time: offsetMinutes, earliest and latest belong to sunrise and sunset");
            return;
        }

        final String kind = value.kind().value();
        require(value.time() == null, end + " is the " + kind + ": time belongs to a clock time");
        require(value.offsetMinutes() != null, end + ".offsetMinutes is missing for the " + kind);
        //not Math.abs: the absolute value of Integer.MIN_VALUE is still negative
        require(value.offsetMinutes() >= -MAX_OFFSET_MINUTES && value.offsetMinutes() <= MAX_OFFSET_MINUTES,
            end + ".offsetMinutes is between -" + MAX_OFFSET_MINUTES + " and " + MAX_OFFSET_MINUTES);
        if (value.earliest() != null && value.latest() != null) {
            require(value.latest().isAfter(value.earliest()), end + ".latest is after " + end + ".earliest");
        }
    }

    private static void checkTargets(final List<ScheduleTarget> targets) {
        require(targets != null && !targets.isEmpty(), "targets has at least one room or blind");
        require(targets.size() <= MAX_TARGETS, "targets has at most " + MAX_TARGETS + " rooms and blinds");

        final Set<ScheduleTarget> seen = new HashSet<>();
        for (final ScheduleTarget target : targets) {
            require(target != null, "a target is missing");
            require((target.room() == null) != (target.deviceId() == null),
                "a target is a room or one blind (deviceId), one of the two");
            require(target.deviceId() == null || target.deviceId() > 0, "deviceId is a positive id of the device registry");
            require(seen.add(target), "a target is listed once: " + describe(target));
        }
    }

    private static String describe(final ScheduleTarget target) {
        return target.room() == null ? "blind " + target.deviceId() : "room " + target.room().getValue();
    }

    private static void checkConditions(final List<ScheduleCondition> conditions) {
        require(conditions != null, "conditions is missing - an empty list for none");
        require(conditions.size() <= MAX_CONDITIONS, "conditions has at most " + MAX_CONDITIONS + " conditions");
        conditions.forEach(ScheduleRules::checkCondition);
    }

    private static void checkCondition(final ScheduleCondition condition) {
        require(condition != null, "a condition is missing");
        require(condition.metric() != null, "a condition has no metric");
        require(condition.operator() != null, "a condition has no operator");
        require(condition.holdMinutes() != null, "a condition has no holdMinutes");
        require(condition.holdMinutes() >= 0 && condition.holdMinutes() <= MAX_HOLD_MINUTES,
            "holdMinutes is between 0 and " + MAX_HOLD_MINUTES);

        final ConditionMetric metric = condition.metric();
        final Range range = rangeOf(metric);
        final String name = metric.value();

        if (range == null) {
            require(condition.operator() == ConditionOperator.IS, name + " is compared with is, not with lt or gt");
            require(condition.value() != null, name + " is compared with a value, true or false");
            require(condition.threshold() == null, name + " is compared with a value, not with a threshold");
            return;
        }

        require(condition.operator() != ConditionOperator.IS, name + " is compared with lt or gt, not with is");
        require(condition.threshold() != null, name + " is compared with a threshold");
        require(condition.value() == null, name + " is compared with a threshold, not with a value");
        require(condition.threshold().stripTrailingZeros().scale() <= 1, "a threshold has at most one decimal");
        require(condition.threshold().compareTo(range.min()) >= 0 && condition.threshold().compareTo(range.max()) <= 0,
            "the threshold of " + name + " is between " + range.min().toPlainString() + " and "
                + range.max().toPlainString() + " " + range.unit());
    }

    //Every metric named: a metric added has to be decided here, or the compiler stops. A truth has
    //no range. The ranges hold what the weather station can report, with room to spare
    private static Range rangeOf(final ConditionMetric metric) {
        return switch (metric) {
            case ILLUMINANCE -> new Range(BigDecimal.ZERO, BigDecimal.valueOf(200_000), "lx");
            case OUTSIDE_TEMPERATURE -> new Range(BigDecimal.valueOf(-50), BigDecimal.valueOf(60), "°C");
            case WIND_GUST -> new Range(BigDecimal.ZERO, BigDecimal.valueOf(100), "m/s");
            case RAINING, WINDOW_CLOSED -> null;
        };
    }

    private static void checkAction(final ScheduleAction action, final Integer position) {
        require(action != null, "action is missing");
        if (action == ScheduleAction.POSITION) {
            require(position != null, "position is missing for the action position");
            require(position >= 0 && position <= 100, "position is between 0 and 100");
        } else {
            require(position == null, "position belongs to the action position, not to " + action.value());
        }
    }

    private static void require(final boolean rule, final String reason) {
        if (!rule) {
            throw BlindsException.invalidSchedule(reason);
        }
    }

    private record Range(BigDecimal min, BigDecimal max, String unit) {
    }
}
