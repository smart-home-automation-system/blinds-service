package cloud.cholewa.blinds.service.schedule;

import cloud.cholewa.blinds.error.BlindsError;
import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.schedule.ConditionMetric;
import cloud.cholewa.blinds.model.schedule.ConditionOperator;
import cloud.cholewa.blinds.model.schedule.Schedule;
import cloud.cholewa.blinds.model.schedule.ScheduleAction;
import cloud.cholewa.blinds.model.schedule.ScheduleCondition;
import cloud.cholewa.blinds.model.schedule.ScheduleTarget;
import cloud.cholewa.blinds.model.schedule.WindowEnd;
import cloud.cholewa.blinds.model.schedule.WindowKind;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static cloud.cholewa.blinds.service.schedule.ScheduleFixtures.sunsetClose;
import static cloud.cholewa.blinds.service.schedule.ScheduleFixtures.summerShading;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

//every rule a schedule is refused by, each with the reason the answer carries
class ScheduleRulesTest {

    private static final LocalTime NOON = LocalTime.of(12, 0);

    @Test
    void should_accept_the_two_schedules_of_the_readme() {
        assertThatCode(() -> ScheduleRules.check(sunsetClose().build())).doesNotThrowAnyException();
        assertThatCode(() -> ScheduleRules.check(summerShading().build())).doesNotThrowAnyException();
    }

    static Stream<Arguments> accepted() {
        return Stream.of(
            arguments(named("no conditions", sunsetClose().conditions(List.of()).build())),
            //two letters and an emoji are three characters to the schema, four units of a string
            arguments(named("a name of three characters", sunsetClose().name("A😀b").build())),
            arguments(named("a name of sixty characters", sunsetClose().name("x".repeat(60)).build())),
            arguments(named("an offset of 180 minutes",
                sunsetClose().from(WindowEnd.sun(WindowKind.SUNSET, 180, null, null)).build())),
            arguments(named("an offset of -180 minutes",
                sunsetClose().from(WindowEnd.sun(WindowKind.SUNRISE, -180, null, null)).build())),
            arguments(named("a time of the sun with only the earliest",
                sunsetClose().from(WindowEnd.sun(WindowKind.SUNSET, 0, LocalTime.of(17, 0), null)).build())),
            //the order is known only on the day: the engine resolves it
            arguments(named("a time of the sun after a clock time",
                sunsetClose().from(WindowEnd.clock(LocalTime.of(22, 0)))
                    .to(WindowEnd.sun(WindowKind.SUNRISE, 0, null, null)).build())),
            arguments(named("a hold of 120 minutes", sunsetClose().conditions(List.of(
                ScheduleCondition.compared(ConditionMetric.WIND_GUST, ConditionOperator.GT, BigDecimal.TEN, 120)))
                .build())),
            arguments(named("a threshold at the bounds", sunsetClose().conditions(List.of(
                ScheduleCondition.compared(ConditionMetric.OUTSIDE_TEMPERATURE, ConditionOperator.LT,
                    BigDecimal.valueOf(-50), 0),
                ScheduleCondition.compared(ConditionMetric.ILLUMINANCE, ConditionOperator.LT,
                    BigDecimal.valueOf(200_000), 0),
                ScheduleCondition.compared(ConditionMetric.WIND_GUST, ConditionOperator.LT,
                    new BigDecimal("100.0"), 0)))
                .build())),
            arguments(named("not raining", sunsetClose().conditions(List.of(
                ScheduleCondition.is(ConditionMetric.RAINING, false, 5))).build())),
            arguments(named("a position of 0", summerShading().position(0).build())),
            arguments(named("a disabled schedule", sunsetClose().enabled(false).build()))
        );
    }

    @ParameterizedTest
    @MethodSource("accepted")
    void should_accept(final Schedule schedule) {
        assertThatCode(() -> ScheduleRules.check(schedule)).doesNotThrowAnyException();
    }

    static Stream<Arguments> refused() {
        return Stream.of(
            refused("no name", sunsetClose().name(null), "name is missing"),
            refused("a blank name", sunsetClose().name("   "), "name is missing"),
            refused("a name of two characters", sunsetClose().name("ab"), "name has 3 to 60 characters"),
            refused("a name of 61 characters", sunsetClose().name("x".repeat(61)), "name has 3 to 60 characters"),
            refused("a name with a space around", sunsetClose().name(" Evening close"),
                "name does not start or end with a space"),
            refused("no enabled", sunsetClose().enabled(null), "enabled is missing"),
            refused("no day type", sunsetClose().dayType(null), "dayType is missing"),

            refused("no window", sunsetClose().noWindow(), "window is missing"),
            refused("no from", sunsetClose().from(null), "window.from is missing"),
            refused("no to", sunsetClose().to(null), "window.to is missing"),
            refused("an end without a kind", sunsetClose().to(new WindowEnd(null, NOON, null, null, null)),
                "window.to.kind is missing"),
            refused("a clock time without a time", sunsetClose().to(new WindowEnd(WindowKind.CLOCK, null, null, null, null)),
                "window.to.time is missing for a clock time"),
            refused("a clock time with an offset", sunsetClose().to(new WindowEnd(WindowKind.CLOCK, NOON, 10, null, null)),
                "window.to is a clock time: offsetMinutes, earliest and latest belong to sunrise and sunset"),
            refused("a clock time with an earliest",
                sunsetClose().to(new WindowEnd(WindowKind.CLOCK, NOON, null, NOON, null)),
                "window.to is a clock time: offsetMinutes, earliest and latest belong to sunrise and sunset"),
            refused("a clock time with a latest",
                sunsetClose().to(new WindowEnd(WindowKind.CLOCK, NOON, null, null, NOON)),
                "window.to is a clock time: offsetMinutes, earliest and latest belong to sunrise and sunset"),
            refused("a time of the sun with a time",
                sunsetClose().from(new WindowEnd(WindowKind.SUNSET, NOON, 0, null, null)),
                "window.from is the sunset: time belongs to a clock time"),
            refused("a time of the sun without an offset",
                sunsetClose().from(new WindowEnd(WindowKind.SUNRISE, null, null, null, null)),
                "window.from.offsetMinutes is missing for the sunrise"),
            refused("an offset of 181 minutes", sunsetClose().from(WindowEnd.sun(WindowKind.SUNSET, 181, null, null)),
                "window.from.offsetMinutes is between -180 and 180"),
            refused("an offset of -181 minutes", sunsetClose().from(WindowEnd.sun(WindowKind.SUNSET, -181, null, null)),
                "window.from.offsetMinutes is between -180 and 180"),
            refused("the lowest int as an offset",
                sunsetClose().from(WindowEnd.sun(WindowKind.SUNSET, Integer.MIN_VALUE, null, null)),
                "window.from.offsetMinutes is between -180 and 180"),
            refused("a latest before the earliest",
                sunsetClose().from(WindowEnd.sun(WindowKind.SUNSET, 0, LocalTime.of(21, 0), LocalTime.of(17, 0))),
                "window.from.latest is after window.from.earliest"),
            refused("a latest at the earliest",
                sunsetClose().from(WindowEnd.sun(WindowKind.SUNSET, 0, LocalTime.of(17, 0), LocalTime.of(17, 0))),
                "window.from.latest is after window.from.earliest"),
            refused("a clock window across midnight", summerShading().from(WindowEnd.clock(LocalTime.of(22, 0)))
                .to(WindowEnd.clock(LocalTime.of(6, 0))), "window.to is after window.from when both are clock times"),
            refused("an empty clock window", summerShading().to(WindowEnd.clock(LocalTime.of(11, 0))),
                "window.to is after window.from when both are clock times"),

            refused("no targets", sunsetClose().targets(null), "targets has at least one room or blind"),
            refused("an empty target list", sunsetClose().targets(List.of()), "targets has at least one room or blind"),
            refused("31 targets", sunsetClose().targets(LongStream.rangeClosed(1, 31).mapToObj(ScheduleTarget::blind).toList()),
                "targets has at most 30 rooms and blinds"),
            refused("a missing target", sunsetClose().targets(Arrays.asList(ScheduleTarget.blind(53), null)),
                "a target is missing"),
            refused("a target with a room and a blind", sunsetClose().targets(List.of(new ScheduleTarget(RoomName.OFFICE, 53L))),
                "a target is a room or one blind (deviceId), one of the two"),
            refused("a target with neither", sunsetClose().targets(List.of(new ScheduleTarget(null, null))),
                "a target is a room or one blind (deviceId), one of the two"),
            refused("a blind with the id 0", sunsetClose().targets(List.of(ScheduleTarget.blind(0))),
                "deviceId is a positive id of the device registry"),
            refused("a room listed twice", sunsetClose().targets(List.of(
                    ScheduleTarget.room(RoomName.OFFICE), ScheduleTarget.blind(53), ScheduleTarget.room(RoomName.OFFICE))),
                "a target is listed once: room office"),
            refused("a blind listed twice", sunsetClose().targets(List.of(ScheduleTarget.blind(53), ScheduleTarget.blind(53))),
                "a target is listed once: blind 53"),

            refused("no conditions", sunsetClose().conditions(null), "conditions is missing - an empty list for none"),
            refused("11 conditions", sunsetClose().conditions(new ArrayList<>(Collections.nCopies(11,
                ScheduleCondition.is(ConditionMetric.RAINING, false, 0)))), "conditions has at most 10 conditions"),
            refused("a missing condition", sunsetClose().conditions(Arrays.asList(
                ScheduleCondition.is(ConditionMetric.RAINING, false, 0), null)), "a condition is missing"),
            refused("a condition without a metric", condition(new ScheduleCondition(null, ConditionOperator.IS, null, true, 0)),
                "a condition has no metric"),
            refused("a condition without an operator",
                condition(new ScheduleCondition(ConditionMetric.RAINING, null, null, true, 0)),
                "a condition has no operator"),
            refused("a condition without a hold",
                condition(new ScheduleCondition(ConditionMetric.RAINING, ConditionOperator.IS, null, true, null)),
                "a condition has no holdMinutes"),
            refused("a hold of -1", condition(ScheduleCondition.is(ConditionMetric.RAINING, true, -1)),
                "holdMinutes is between 0 and 120"),
            refused("a hold of 121", condition(ScheduleCondition.is(ConditionMetric.RAINING, true, 121)),
                "holdMinutes is between 0 and 120"),
            refused("a measured metric with is", condition(new ScheduleCondition(
                    ConditionMetric.ILLUMINANCE, ConditionOperator.IS, null, true, 0)),
                "illuminance is compared with lt or gt, not with is"),
            refused("a measured metric without a threshold", condition(new ScheduleCondition(
                    ConditionMetric.OUTSIDE_TEMPERATURE, ConditionOperator.GT, null, null, 0)),
                "outside temperature is compared with a threshold"),
            refused("a measured metric with a value", condition(new ScheduleCondition(
                    ConditionMetric.WIND_GUST, ConditionOperator.GT, BigDecimal.TEN, true, 0)),
                "wind gust is compared with a threshold, not with a value"),
            refused("a threshold of two decimals", compared(ConditionMetric.OUTSIDE_TEMPERATURE, "20.25"),
                "a threshold has at most one decimal"),
            refused("a negative illuminance", compared(ConditionMetric.ILLUMINANCE, "-1"),
                "the threshold of illuminance is between 0 and 200000 lx"),
            refused("an illuminance above the range", compared(ConditionMetric.ILLUMINANCE, "200000.1"),
                "the threshold of illuminance is between 0 and 200000 lx"),
            refused("a temperature below the range", compared(ConditionMetric.OUTSIDE_TEMPERATURE, "-50.1"),
                "the threshold of outside temperature is between -50 and 60 °C"),
            refused("a temperature above the range", compared(ConditionMetric.OUTSIDE_TEMPERATURE, "61"),
                "the threshold of outside temperature is between -50 and 60 °C"),
            refused("a negative gust", compared(ConditionMetric.WIND_GUST, "-0.5"),
                "the threshold of wind gust is between 0 and 100 m/s"),
            refused("a gust above the range", compared(ConditionMetric.WIND_GUST, "101"),
                "the threshold of wind gust is between 0 and 100 m/s"),
            refused("a truth with lt", condition(new ScheduleCondition(
                    ConditionMetric.RAINING, ConditionOperator.LT, BigDecimal.ONE, null, 0)),
                "raining is compared with is, not with lt or gt"),
            refused("a truth without a value", condition(new ScheduleCondition(
                    ConditionMetric.WINDOW_CLOSED, ConditionOperator.IS, null, null, 0)),
                "window closed is compared with a value, true or false"),
            refused("a truth with a threshold", condition(new ScheduleCondition(
                    ConditionMetric.WINDOW_CLOSED, ConditionOperator.IS, BigDecimal.ONE, true, 0)),
                "window closed is compared with a value, not with a threshold"),

            refused("no action", sunsetClose().action(null), "action is missing"),
            refused("a position without one", summerShading().position(null), "position is missing for the action position"),
            refused("a position of 101", summerShading().position(101), "position is between 0 and 100"),
            refused("a position of -1", summerShading().position(-1), "position is between 0 and 100"),
            refused("a position with open", sunsetClose().action(ScheduleAction.OPEN).position(50),
                "position belongs to the action position, not to open")
        );
    }

    @ParameterizedTest
    @MethodSource("refused")
    void should_refuse_with_the_reason(final Schedule schedule, final String reason) {
        final BlindsException refusal = catchThrowableOfType(BlindsException.class, () -> ScheduleRules.check(schedule));

        assertThat(refusal).isNotNull();
        assertThat(refusal.getError()).isEqualTo(BlindsError.INVALID_SCHEDULE);
        assertThat(refusal.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refusal.getMessage()).isEqualTo(reason);
    }

    private static Arguments refused(final String name, final ScheduleFixtures.Builder schedule, final String reason) {
        return arguments(named(name, schedule.build()), reason);
    }

    private static ScheduleFixtures.Builder condition(final ScheduleCondition condition) {
        return sunsetClose().conditions(List.of(condition));
    }

    private static ScheduleFixtures.Builder compared(final ConditionMetric metric, final String threshold) {
        return condition(ScheduleCondition.compared(metric, ConditionOperator.GT, new BigDecimal(threshold), 0));
    }
}
