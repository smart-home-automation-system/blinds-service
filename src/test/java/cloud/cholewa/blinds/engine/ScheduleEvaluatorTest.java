package cloud.cholewa.blinds.engine;

import cloud.cholewa.blinds.engine.ScheduleEvaluator.ConditionStatus;
import cloud.cholewa.blinds.engine.ScheduleEvaluator.Occurrence;
import cloud.cholewa.blinds.engine.ScheduleEvaluator.ResolvedWindow;
import cloud.cholewa.blinds.engine.ScheduleEvaluator.Truth;
import cloud.cholewa.blinds.engine.ScheduleEvaluator.Verdict;
import cloud.cholewa.blinds.engine.WeatherNow.Observation;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.WindowState;
import cloud.cholewa.blinds.model.schedule.ConditionMetric;
import cloud.cholewa.blinds.model.schedule.ConditionOperator;
import cloud.cholewa.blinds.model.schedule.ScheduleCondition;
import cloud.cholewa.blinds.model.schedule.ScheduleDayType;
import cloud.cholewa.blinds.model.schedule.ScheduleTarget;
import cloud.cholewa.blinds.model.schedule.ScheduleWindow;
import cloud.cholewa.blinds.model.schedule.WindowEnd;
import cloud.cholewa.blinds.model.schedule.WindowKind;
import cloud.cholewa.home.model.BlindsPlacement;
import cloud.cholewa.home.model.DayType;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

//Every rule of the engine without I/O: the window kinds and their clamps, the day types, the truth
//of a condition and its hold, the order of the decision and the once-a-day of a target
class ScheduleEvaluatorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final SunDay SUN = new SunDay(DAY, LocalTime.of(6, 58), LocalTime.of(17, 17));
    private static final LocalDateTime NOW = DAY.atTime(17, 0);

    @Nested
    class Window {

        @Test
        void should_take_a_clock_time_as_it_is() {
            assertThat(ScheduleEvaluator.window(new ScheduleWindow(clock(7, 0), clock(9, 30)), SUN))
                .contains(new ResolvedWindow(LocalTime.of(7, 0), LocalTime.of(9, 30)));
        }

        @Test
        void should_move_the_sunrise_and_the_sunset_by_their_offsets() {
            final ScheduleWindow window = new ScheduleWindow(
                WindowEnd.sun(WindowKind.SUNRISE, -15, null, null),
                WindowEnd.sun(WindowKind.SUNSET, 45, null, null));

            assertThat(ScheduleEvaluator.window(window, SUN))
                .contains(new ResolvedWindow(LocalTime.of(6, 43), LocalTime.of(18, 2)));
        }

        @ParameterizedTest
        @CsvSource({
            //sunset 17:17 - 30 = 16:47: held at the earliest
            "-30, 17:00, 21:30, 17:00",
            //sunset 17:17 + 180 = 20:17: held at the latest
            "180, 17:00, 19:00, 19:00",
            //inside both
            "0, 17:00, 21:30, 17:17",
            //no bounds
            "-120, , , 15:17"
        })
        void should_hold_a_time_of_the_sun_between_earliest_and_latest(
            final int offset, final LocalTime earliest, final LocalTime latest, final LocalTime expected) {
            assertThat(ScheduleEvaluator.end(WindowEnd.sun(WindowKind.SUNSET, offset, earliest, latest), SUN))
                .isEqualTo(expected);
        }

        //never wrapped into the small hours of the same day
        @Test
        void should_keep_a_time_of_the_sun_moved_past_midnight_within_the_day() {
            final SunDay summer = new SunDay(DAY, LocalTime.of(4, 30), LocalTime.of(22, 30));

            assertThat(ScheduleEvaluator.end(WindowEnd.sun(WindowKind.SUNSET, 180, null, null), summer))
                .isEqualTo(LocalTime.of(23, 59));
            assertThat(ScheduleEvaluator.end(WindowEnd.sun(WindowKind.SUNRISE, -180, null, null), new SunDay(DAY,
                LocalTime.of(2, 0), LocalTime.of(22, 0)))).isEqualTo(LocalTime.MIDNIGHT);
        }

        @Test
        void should_have_no_window_when_the_day_has_no_such_time_of_the_sun() {
            final SunDay polarNight = new SunDay(DAY, null, null);

            assertThat(ScheduleEvaluator.window(new ScheduleWindow(
                WindowEnd.sun(WindowKind.SUNSET, 0, null, null), clock(23, 0)), polarNight)).isEmpty();
        }

        //HAS-290: an end before the start is the next day; an end at the start is no window
        @Test
        void should_see_a_window_that_ends_before_it_starts_as_one_across_midnight() {
            final Optional<ResolvedWindow> night = ScheduleEvaluator.window(new ScheduleWindow(
                clock(17, 30), WindowEnd.sun(WindowKind.SUNSET, 0, null, null)), SUN);
            final Optional<ResolvedWindow> none = ScheduleEvaluator.window(new ScheduleWindow(
                clock(17, 17), WindowEnd.sun(WindowKind.SUNSET, 0, null, null)), SUN);

            assertThat(night).hasValueSatisfying(resolved -> {
                assertThat(resolved.isEmpty()).isFalse();
                assertThat(resolved.crossesMidnight()).isTrue();
            });
            assertThat(none).hasValueSatisfying(resolved -> assertThat(resolved.isEmpty()).isTrue());
        }
    }

    //HAS-290: a window of 21:00 to 06:00 belongs to the day it opened on
    @Nested
    class AcrossMidnight {

        private final ScheduleWindow night = new ScheduleWindow(clock(21, 0), clock(6, 0));
        private final SunDay nextSun = new SunDay(DAY.plusDays(1), LocalTime.of(7, 0), LocalTime.of(17, 15));

        @Test
        void should_end_a_night_window_on_the_next_day() {
            assertThat(ScheduleEvaluator.occurrence(night, DAY, SUN, nextSun)).hasValueSatisfying(occurrence -> {
                assertThat(occurrence.overnight()).isTrue();
                assertThat(occurrence.start()).isEqualTo(DAY.atTime(21, 0));
                assertThat(occurrence.end()).isEqualTo(DAY.plusDays(1).atTime(6, 0));
                assertThat(occurrence.contains(DAY.atTime(23, 59))).isTrue();
                assertThat(occurrence.contains(DAY.plusDays(1).atTime(0, 0))).isTrue();
                assertThat(occurrence.contains(DAY.plusDays(1).atTime(5, 59, 59))).isTrue();
                assertThat(occurrence.contains(DAY.plusDays(1).atTime(6, 0))).isFalse();
                assertThat(occurrence.contains(DAY.atTime(20, 59))).isFalse();
            });
        }

        //the sunset to the sunrise: the end is the sunrise of the next day, not of the first
        @Test
        void should_take_a_time_of_the_sun_after_midnight_from_the_next_day() {
            final ScheduleWindow sunsetToSunrise = new ScheduleWindow(WindowEnd.sun(WindowKind.SUNSET, 0, null, null),
                WindowEnd.sun(WindowKind.SUNRISE, 0, null, null));

            assertThat(ScheduleEvaluator.occurrence(sunsetToSunrise, DAY, SUN, nextSun)).hasValueSatisfying(occurrence -> {
                assertThat(occurrence.start()).isEqualTo(DAY.atTime(17, 17));
                assertThat(occurrence.end()).isEqualTo(DAY.plusDays(1).atTime(7, 0));
            });
        }

        @Test
        void should_keep_a_window_within_one_day_as_it_was() {
            assertThat(ScheduleEvaluator.occurrence(new ScheduleWindow(clock(16, 0), clock(18, 0)), DAY, SUN, nextSun))
                .hasValue(new Occurrence(DAY, LocalTime.of(16, 0), LocalTime.of(18, 0), false));
            assertThat(ScheduleEvaluator.occurrence(new ScheduleWindow(clock(16, 0), clock(16, 0)), DAY, SUN, nextSun))
                .hasValueSatisfying(occurrence -> assertThat(occurrence.isEmpty()).isTrue());
        }

        //after midnight the night of yesterday is the one judged - on a day it runs on
        @Test
        void should_judge_the_night_that_opened_yesterday_until_it_ends() {
            final Optional<Occurrence> yesterday = ScheduleEvaluator.occurrence(night, DAY.minusDays(1), SUN, SUN);
            final Optional<Occurrence> today = ScheduleEvaluator.occurrence(night, DAY, SUN, nextSun);

            assertThat(ScheduleEvaluator.current(yesterday, true, today, DAY.atTime(2, 0))).isEqualTo(yesterday);
            assertThat(ScheduleEvaluator.current(yesterday, true, today, DAY.atTime(6, 0))).isEqualTo(today);
            assertThat(ScheduleEvaluator.current(yesterday, false, today, DAY.atTime(2, 0))).isEqualTo(today);
            assertThat(ScheduleEvaluator.current(Optional.empty(), true, today, DAY.atTime(2, 0))).isEqualTo(today);
        }

        @Test
        void should_decide_by_the_night_and_not_by_the_clock_of_the_day() {
            final Optional<Occurrence> occurrence = ScheduleEvaluator.occurrence(night, DAY, SUN, nextSun);

            assertThat(ScheduleEvaluator.decide(true, true, occurrence, DAY.atTime(20, 0), 1, 1, 1)).isEqualTo(Verdict.BEFORE_WINDOW);
            assertThat(ScheduleEvaluator.decide(true, true, occurrence, DAY.atTime(22, 0), 1, 1, 1)).isEqualTo(Verdict.FIRE);
            assertThat(ScheduleEvaluator.decide(true, true, occurrence, DAY.plusDays(1).atTime(1, 0), 1, 1, 1)).isEqualTo(Verdict.FIRE);
            assertThat(ScheduleEvaluator.decide(true, true, occurrence, DAY.plusDays(1).atTime(1, 0), 1, 0, 0)).isEqualTo(Verdict.FIRED);
            assertThat(ScheduleEvaluator.decide(true, true, occurrence, DAY.plusDays(1).atTime(6, 0), 1, 1, 1))
                .isEqualTo(Verdict.AFTER_WINDOW);
        }
    }

    @Nested
    class Days {

        @ParameterizedTest
        @CsvSource({
            "ANY, WORKDAY, true",
            "ANY, DAY_OFF, true",
            "WORKDAY, WORKDAY, true",
            "WORKDAY, DAY_OFF, false",
            "DAY_OFF, DAY_OFF, true",
            "DAY_OFF, WORKDAY, false"
        })
        void should_run_on_the_days_of_its_type(final ScheduleDayType schedule, final DayType today, final boolean runs) {
            assertThat(ScheduleEvaluator.runsOn(schedule, today)).isEqualTo(runs);
        }

        @Test
        void should_judge_a_day_by_its_weekday_alone() {
            assertThat(DayTypes.byWeekday(LocalDate.of(2026, 10, 16))).isEqualTo(DayType.WORKDAY);
            assertThat(DayTypes.byWeekday(LocalDate.of(2026, 10, 17))).isEqualTo(DayType.DAY_OFF);
            assertThat(DayTypes.byWeekday(LocalDate.of(2026, 10, 18))).isEqualTo(DayType.DAY_OFF);
            //a holiday is a workday by its weekday - only the calendar knows it
            assertThat(DayTypes.byWeekday(LocalDate.of(2026, 11, 11))).isEqualTo(DayType.WORKDAY);
        }
    }

    @Nested
    class Truths {

        @ParameterizedTest
        @CsvSource({
            "LT, 150, TRUE",
            "LT, 200, FALSE",
            "LT, 250, FALSE",
            "GT, 250, TRUE",
            "GT, 200, FALSE",
            "GT, 150, FALSE"
        })
        void should_compare_a_metric_with_its_threshold_strictly(
            final ConditionOperator operator, final int value, final Truth expected) {
            final ScheduleCondition condition = ScheduleCondition.compared(ConditionMetric.ILLUMINANCE, operator,
                BigDecimal.valueOf(200), 0);

            assertThat(ScheduleEvaluator.truth(condition, weather(ConditionMetric.ILLUMINANCE, value, NOW), NOW))
                .isEqualTo(expected);
        }

        //by the text of the number: 25.5 is not above 25.5, whatever double stands for it
        @Test
        void should_compare_a_fraction_exactly() {
            final ScheduleCondition condition = ScheduleCondition.compared(ConditionMetric.OUTSIDE_TEMPERATURE,
                ConditionOperator.GT, new BigDecimal("25.5"), 0);

            assertThat(ScheduleEvaluator.truth(condition, weather(ConditionMetric.OUTSIDE_TEMPERATURE, 25.5, NOW), NOW))
                .isEqualTo(Truth.FALSE);
            assertThat(ScheduleEvaluator.truth(condition, weather(ConditionMetric.OUTSIDE_TEMPERATURE, 25.6, NOW), NOW))
                .isEqualTo(Truth.TRUE);
        }

        @ParameterizedTest
        @CsvSource({"true, true, TRUE", "true, false, FALSE", "false, false, TRUE", "false, true, FALSE"})
        void should_compare_the_rain_with_its_expected_value(final boolean expected, final boolean raining, final Truth truth) {
            final ScheduleCondition condition = ScheduleCondition.is(ConditionMetric.RAINING, expected, 0);

            assertThat(ScheduleEvaluator.truth(condition, weather(ConditionMetric.RAINING, raining, NOW), NOW)).isEqualTo(truth);
        }

        @Test
        void should_not_know_a_metric_the_weather_did_not_bring() {
            final ScheduleCondition condition = ScheduleCondition.compared(ConditionMetric.WIND_GUST, ConditionOperator.GT,
                BigDecimal.TEN, 0);

            assertThat(ScheduleEvaluator.truth(condition, weather(ConditionMetric.ILLUMINANCE, 100, NOW), NOW))
                .isEqualTo(Truth.UNKNOWN);
            assertThat(ScheduleEvaluator.truth(condition, WeatherNow.unknown(), NOW)).isEqualTo(Truth.UNKNOWN);
        }

        //ten minutes is the limit itself: a value measured exactly then still counts
        @Test
        void should_not_know_a_metric_older_than_ten_minutes() {
            final ScheduleCondition condition = ScheduleCondition.compared(ConditionMetric.ILLUMINANCE, ConditionOperator.LT,
                BigDecimal.valueOf(200), 0);

            assertThat(ScheduleEvaluator.truth(condition, weather(ConditionMetric.ILLUMINANCE, 100, NOW.minusMinutes(10)), NOW))
                .isEqualTo(Truth.TRUE);
            assertThat(ScheduleEvaluator.truth(condition,
                weather(ConditionMetric.ILLUMINANCE, 100, NOW.minusMinutes(10).minusSeconds(1)), NOW)).isEqualTo(Truth.UNKNOWN);
        }

        @Test
        void should_not_know_a_metric_weather_service_holds_stale() {
            final ScheduleCondition condition = ScheduleCondition.compared(ConditionMetric.ILLUMINANCE, ConditionOperator.LT,
                BigDecimal.valueOf(200), 0);
            final WeatherNow stale = new WeatherNow(Map.of(ConditionMetric.ILLUMINANCE, new Observation(100, NOW, true)), NOW);

            assertThat(ScheduleEvaluator.truth(condition, stale, NOW)).isEqualTo(Truth.UNKNOWN);
        }

        @Test
        void should_not_know_a_value_of_the_wrong_kind() {
            final ScheduleCondition lux = ScheduleCondition.compared(ConditionMetric.ILLUMINANCE, ConditionOperator.LT,
                BigDecimal.valueOf(200), 0);
            final ScheduleCondition rain = ScheduleCondition.is(ConditionMetric.RAINING, true, 0);

            assertThat(ScheduleEvaluator.truth(lux, weather(ConditionMetric.ILLUMINANCE, "dark", NOW), NOW)).isEqualTo(Truth.UNKNOWN);
            assertThat(ScheduleEvaluator.truth(lux, weather(ConditionMetric.ILLUMINANCE, Double.NaN, NOW), NOW)).isEqualTo(Truth.UNKNOWN);
            assertThat(ScheduleEvaluator.truth(rain, weather(ConditionMetric.RAINING, 1, NOW), NOW)).isEqualTo(Truth.UNKNOWN);
        }

        //without the blind it is about, the window is not known
        @Test
        void should_not_know_the_window_without_its_blind() {
            assertThat(ScheduleEvaluator.truth(ScheduleCondition.is(ConditionMetric.WINDOW_CLOSED, true, 0),
                weather(ConditionMetric.ILLUMINANCE, 100, NOW), NOW)).isEqualTo(Truth.UNKNOWN);
        }

        //HAS-290, the window of one blind: no sensor counts as closed; every sensor closed - closed;
        //one open - open; one silent - unknown (WindowSensorsTest judges the sensors into the state)
        @ParameterizedTest
        @CsvSource({
            "NO_SENSOR, true, TRUE",
            "CLOSED, true, TRUE",
            "OPEN, true, FALSE",
            "UNKNOWN, true, UNKNOWN",
            "NO_SENSOR, false, FALSE",
            "CLOSED, false, FALSE",
            "OPEN, false, TRUE",
            "UNKNOWN, false, UNKNOWN"
        })
        void should_judge_window_closed_by_the_window_of_the_blind(final WindowState window, final boolean value, final Truth truth) {
            assertThat(ScheduleEvaluator.truth(ScheduleCondition.is(ConditionMetric.WINDOW_CLOSED, value, 0),
                WeatherNow.unknown(), window, NOW)).isEqualTo(truth);
        }

        @Test
        void should_judge_the_conditions_per_blind_only_where_window_closed_is_one_of_them() {
            final ScheduleCondition lux = ScheduleCondition.compared(ConditionMetric.ILLUMINANCE, ConditionOperator.LT,
                BigDecimal.valueOf(200), 0);

            assertThat(ScheduleEvaluator.perBlind(List.of(lux))).isFalse();
            assertThat(ScheduleEvaluator.perBlind(List.of(lux, ScheduleCondition.is(ConditionMetric.WINDOW_CLOSED, true, 0))))
                .isTrue();
        }
    }

    @Nested
    class Holds {

        private final ScheduleCondition tenMinutes = ScheduleCondition.compared(ConditionMetric.ILLUMINANCE,
            ConditionOperator.LT, BigDecimal.valueOf(200), 10);

        @Test
        void should_be_holding_until_the_hold_is_over_and_met_from_then_on() {
            final LocalDateTime since = NOW.minusMinutes(10);

            assertThat(ScheduleEvaluator.status(tenMinutes, Truth.TRUE, since, NOW.minusSeconds(1))).isEqualTo(ConditionStatus.HOLDING);
            assertThat(ScheduleEvaluator.status(tenMinutes, Truth.TRUE, since, NOW)).isEqualTo(ConditionStatus.MET);
        }

        @Test
        void should_count_a_condition_not_seen_true_before_from_now() {
            assertThat(ScheduleEvaluator.status(tenMinutes, Truth.TRUE, null, NOW)).isEqualTo(ConditionStatus.HOLDING);
            assertThat(ScheduleEvaluator.status(ScheduleCondition.compared(ConditionMetric.ILLUMINANCE, ConditionOperator.LT,
                BigDecimal.valueOf(200), 0), Truth.TRUE, null, NOW)).isEqualTo(ConditionStatus.MET);
        }

        @Test
        void should_not_hold_what_is_false_or_unknown() {
            assertThat(ScheduleEvaluator.status(tenMinutes, Truth.FALSE, null, NOW)).isEqualTo(ConditionStatus.NOT_MET);
            assertThat(ScheduleEvaluator.status(tenMinutes, Truth.UNKNOWN, null, NOW)).isEqualTo(ConditionStatus.UNKNOWN);
        }

        @Test
        void should_start_a_hold_again_after_a_break() {
            final HoldTracker tracker = new HoldTracker();
            final HoldTracker.Key key = new HoldTracker.Key(1, tenMinutes);

            assertThat(tracker.observe(key, Truth.TRUE, NOW)).isEqualTo(NOW);
            assertThat(tracker.observe(key, Truth.TRUE, NOW.plusMinutes(1))).isEqualTo(NOW);
            assertThat(tracker.observe(key, Truth.UNKNOWN, NOW.plusMinutes(2))).isNull();
            assertThat(tracker.observe(key, Truth.TRUE, NOW.plusMinutes(3))).isEqualTo(NOW.plusMinutes(3));
        }

        @Test
        void should_forget_the_holds_of_a_condition_no_longer_there() {
            final HoldTracker tracker = new HoldTracker();
            final HoldTracker.Key kept = new HoldTracker.Key(1, tenMinutes);
            final HoldTracker.Key replaced = new HoldTracker.Key(2, tenMinutes);
            tracker.observe(kept, Truth.TRUE, NOW);
            tracker.observe(replaced, Truth.TRUE, NOW);

            tracker.retain(java.util.Set.of(kept));

            assertThat(tracker.since(kept)).isEqualTo(NOW);
            assertThat(tracker.since(replaced)).isNull();
        }
    }

    @Nested
    class Decision {

        private final Optional<Occurrence> window = Optional.of(new Occurrence(DAY, LocalTime.of(16, 0), LocalTime.of(18, 0), false));

        private LocalDateTime at(final int hour, final int minute) {
            return DAY.atTime(hour, minute);
        }

        @Test
        void should_fire_inside_its_window_when_a_pending_blind_is_ready() {
            assertThat(ScheduleEvaluator.decide(true, true, window, at(17, 0), 2, 2, 2)).isEqualTo(Verdict.FIRE);
            assertThat(ScheduleEvaluator.decide(true, true, window, at(17, 0), 2, 1, 1)).isEqualTo(Verdict.FIRE);
        }

        @Test
        void should_take_the_start_of_the_window_in_and_its_end_out() {
            assertThat(ScheduleEvaluator.decide(true, true, window, at(16, 0), 1, 1, 1)).isEqualTo(Verdict.FIRE);
            assertThat(ScheduleEvaluator.decide(true, true, window, DAY.atTime(15, 59, 59), 1, 1, 1))
                .isEqualTo(Verdict.BEFORE_WINDOW);
            assertThat(ScheduleEvaluator.decide(true, true, window, at(18, 0), 1, 1, 1)).isEqualTo(Verdict.AFTER_WINDOW);
        }

        //no blind with every condition met: one condition not met, or every window open
        @Test
        void should_wait_while_no_pending_blind_is_ready() {
            assertThat(ScheduleEvaluator.decide(true, true, window, at(17, 0), 2, 2, 0)).isEqualTo(Verdict.WAITING);
        }

        @ParameterizedTest
        @CsvSource({"HOLDING", "NOT_MET", "UNKNOWN", "PER_BLIND"})
        void should_count_a_blind_ready_only_when_every_condition_is_met(final ConditionStatus other) {
            assertThat(ScheduleEvaluator.ready(List.of(ConditionStatus.MET, ConditionStatus.MET))).isTrue();
            assertThat(ScheduleEvaluator.ready(List.of())).isTrue();
            assertThat(ScheduleEvaluator.ready(List.of(ConditionStatus.MET, other))).isFalse();
        }

        @Test
        void should_fire_once_a_window_per_blind() {
            assertThat(ScheduleEvaluator.decide(true, true, window, at(17, 0), 2, 0, 0)).isEqualTo(Verdict.FIRED);
        }

        @Test
        void should_say_why_it_does_not_fire_in_this_order() {
            final LocalDateTime inside = at(17, 0);

            assertThat(ScheduleEvaluator.decide(false, false, Optional.empty(), inside, 0, 0, 0)).isEqualTo(Verdict.DISABLED);
            assertThat(ScheduleEvaluator.decide(true, false, Optional.empty(), inside, 0, 0, 0)).isEqualTo(Verdict.NOT_TODAY);
            assertThat(ScheduleEvaluator.decide(true, true, Optional.empty(), inside, 1, 1, 1)).isEqualTo(Verdict.NO_WINDOW);
            assertThat(ScheduleEvaluator.decide(true, true, Optional.of(new Occurrence(DAY, LocalTime.of(17, 0),
                LocalTime.of(17, 0), false)), inside, 1, 1, 1)).isEqualTo(Verdict.NO_WINDOW);
            assertThat(ScheduleEvaluator.decide(true, true, window, at(19, 0), 1, 0, 0)).isEqualTo(Verdict.FIRED);
            assertThat(ScheduleEvaluator.decide(true, true, window, inside, 0, 0, 0)).isEqualTo(Verdict.NO_BLINDS);
        }
    }

    @Nested
    class Targets {

        private final Blind kitchen = blind(17, RoomName.KITCHEN);
        private final Blind office = blind(52, RoomName.OFFICE);
        private final Blind officeDoor = blind(53, RoomName.OFFICE);

        @Test
        void should_move_every_blind_of_a_room_and_a_named_blind_once() {
            final List<Blind> blinds = ScheduleEvaluator.blindsOf(
                List.of(ScheduleTarget.blind(53), ScheduleTarget.room(RoomName.OFFICE), ScheduleTarget.blind(17)),
                List.of(kitchen, office, officeDoor));

            assertThat(blinds).containsExactly(officeDoor, office, kitchen);
        }

        @Test
        void should_skip_a_target_the_registry_does_not_know() {
            assertThat(ScheduleEvaluator.blindsOf(List.of(ScheduleTarget.blind(99), ScheduleTarget.room(RoomName.BEDROOM)),
                List.of(kitchen, office))).isEmpty();
        }
    }

    private static WindowEnd clock(final int hour, final int minute) {
        return WindowEnd.clock(LocalTime.of(hour, minute));
    }

    private static WeatherNow weather(final ConditionMetric metric, final Object value, final LocalDateTime measuredAt) {
        return new WeatherNow(Map.of(metric, new Observation(value, measuredAt, false)), measuredAt);
    }

    static Blind blind(final long id, final RoomName room) {
        return new Blind(id, "Blind " + id, room, SmartVendor.EATON, new BlindBinding.Eaton((int) id),
            BlindsPlacement.EXTERNAL, false);
    }
}
