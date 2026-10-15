package cloud.cholewa.blinds.service.schedule;

import cloud.cholewa.blinds.model.schedule.ConditionMetric;
import cloud.cholewa.blinds.model.schedule.ConditionOperator;
import cloud.cholewa.blinds.model.schedule.Schedule;
import cloud.cholewa.blinds.model.schedule.ScheduleAction;
import cloud.cholewa.blinds.model.schedule.ScheduleCondition;
import cloud.cholewa.blinds.model.schedule.ScheduleDayType;
import cloud.cholewa.blinds.model.schedule.ScheduleTarget;
import cloud.cholewa.blinds.model.schedule.ScheduleWindow;
import cloud.cholewa.blinds.model.schedule.WindowEnd;
import cloud.cholewa.blinds.model.schedule.WindowKind;
import cloud.cholewa.home.model.RoomName;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

//the two schedules of the README, and a builder that breaks one field of them at a time
public final class ScheduleFixtures {

    private ScheduleFixtures() {
    }

    //close the living room and the office door half an hour before sunset, not before 17:00 and
    //not after 21:30, once it has been darker than 200 lx for ten minutes
    public static Builder sunsetClose() {
        return new Builder()
            .name("Evening close")
            .enabled(true)
            .dayType(ScheduleDayType.ANY)
            .from(WindowEnd.sun(WindowKind.SUNSET, -30, LocalTime.of(17, 0), LocalTime.of(21, 30)))
            .to(WindowEnd.clock(LocalTime.of(23, 0)))
            .targets(List.of(ScheduleTarget.room(RoomName.LIVING_ROOM), ScheduleTarget.blind(53)))
            .conditions(List.of(ScheduleCondition.compared(ConditionMetric.ILLUMINANCE, ConditionOperator.LT,
                BigDecimal.valueOf(200), 10)))
            .action(ScheduleAction.CLOSE);
    }

    //on a workday between 11:00 and 16:00, half-close the office when it has been warmer than 25 °C
    //for half an hour and brighter than 40 000 lx for a quarter, with its window closed
    public static Builder summerShading() {
        return new Builder()
            .name("Summer shading")
            .enabled(true)
            .dayType(ScheduleDayType.WORKDAY)
            .from(WindowEnd.clock(LocalTime.of(11, 0)))
            .to(WindowEnd.clock(LocalTime.of(16, 0)))
            .targets(List.of(ScheduleTarget.room(RoomName.OFFICE)))
            .conditions(List.of(
                ScheduleCondition.compared(ConditionMetric.OUTSIDE_TEMPERATURE, ConditionOperator.GT,
                    new BigDecimal("25.5"), 30),
                ScheduleCondition.compared(ConditionMetric.ILLUMINANCE, ConditionOperator.GT,
                    BigDecimal.valueOf(40_000), 15),
                ScheduleCondition.is(ConditionMetric.WINDOW_CLOSED, true, 0)))
            .action(ScheduleAction.POSITION)
            .position(30);
    }

    public static final class Builder {

        private String name;
        private Boolean enabled;
        private ScheduleDayType dayType;
        private WindowEnd from;
        private WindowEnd to;
        private boolean noWindow;
        private List<ScheduleTarget> targets;
        private List<ScheduleCondition> conditions;
        private ScheduleAction action;
        private Integer position;

        public Builder name(final String name) {
            this.name = name;
            return this;
        }

        public Builder enabled(final Boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder dayType(final ScheduleDayType dayType) {
            this.dayType = dayType;
            return this;
        }

        public Builder from(final WindowEnd from) {
            this.from = from;
            return this;
        }

        public Builder to(final WindowEnd to) {
            this.to = to;
            return this;
        }

        public Builder noWindow() {
            this.noWindow = true;
            return this;
        }

        public Builder targets(final List<ScheduleTarget> targets) {
            this.targets = targets;
            return this;
        }

        public Builder conditions(final List<ScheduleCondition> conditions) {
            this.conditions = conditions;
            return this;
        }

        public Builder action(final ScheduleAction action) {
            this.action = action;
            return this;
        }

        public Builder position(final Integer position) {
            this.position = position;
            return this;
        }

        public Schedule build() {
            return new Schedule(null, name, enabled, dayType, noWindow ? null : new ScheduleWindow(from, to),
                targets, conditions, action, position, null, null);
        }
    }
}
