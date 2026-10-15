package cloud.cholewa.blinds.service.schedule;

import cloud.cholewa.blinds.database.model.ScheduleConditionEntity;
import cloud.cholewa.blinds.database.model.ScheduleEntity;
import cloud.cholewa.blinds.database.model.ScheduleTargetEntity;
import cloud.cholewa.blinds.model.schedule.Schedule;
import cloud.cholewa.blinds.model.schedule.ScheduleCondition;
import cloud.cholewa.blinds.model.schedule.ScheduleTarget;
import cloud.cholewa.blinds.model.schedule.ScheduleWindow;
import cloud.cholewa.blinds.model.schedule.WindowEnd;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

//between a schedule of the API and its three tables; the schedule has passed ScheduleRules
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ScheduleMapper {

    static ScheduleEntity toEntity(final Long id, final Schedule schedule, final LocalDateTime createdAt,
        final LocalDateTime updatedAt) {
        final WindowEnd from = schedule.window().from();
        final WindowEnd to = schedule.window().to();
        return new ScheduleEntity(
            id,
            schedule.name(),
            schedule.enabled(),
            schedule.dayType(),
            from.kind(), from.time(), from.offsetMinutes(), from.earliest(), from.latest(),
            to.kind(), to.time(), to.offsetMinutes(), to.earliest(), to.latest(),
            schedule.action(),
            schedule.position(),
            createdAt,
            updatedAt
        );
    }

    static List<ScheduleTargetEntity> toTargets(final long scheduleId, final Schedule schedule) {
        return schedule.targets().stream()
            .map(target -> new ScheduleTargetEntity(null, scheduleId, target.room(), target.deviceId()))
            .toList();
    }

    static List<ScheduleConditionEntity> toConditions(final long scheduleId, final Schedule schedule) {
        return schedule.conditions().stream()
            .map(condition -> new ScheduleConditionEntity(null, scheduleId, condition.metric(), condition.operator(),
                condition.threshold(), condition.value(), condition.holdMinutes()))
            .toList();
    }

    static Schedule toSchedule(final ScheduleEntity entity, final List<ScheduleTargetEntity> targets,
        final List<ScheduleConditionEntity> conditions) {
        return new Schedule(
            entity.id(),
            entity.name(),
            entity.enabled(),
            entity.dayType(),
            new ScheduleWindow(
                new WindowEnd(entity.windowFromKind(), entity.windowFromTime(), entity.windowFromOffsetMin(),
                    entity.windowFromEarliest(), entity.windowFromLatest()),
                new WindowEnd(entity.windowToKind(), entity.windowToTime(), entity.windowToOffsetMin(),
                    entity.windowToEarliest(), entity.windowToLatest())
            ),
            targets.stream().map(target -> new ScheduleTarget(target.room(), target.deviceId())).toList(),
            conditions.stream()
                .map(condition -> new ScheduleCondition(condition.metric(), condition.operator(),
                    asGiven(condition.threshold()),
                    condition.expected(), condition.holdMinutes()))
                .toList(),
            entity.action(),
            entity.position(),
            entity.createdAt(),
            entity.updatedAt()
        );
    }

    //the column keeps one decimal, so 200 comes back as 200.0: answered as it was given, and never
    //as 2E+2, which is what stripping the zeros alone leaves
    private static BigDecimal asGiven(final BigDecimal threshold) {
        if (threshold == null) {
            return null;
        }
        final BigDecimal stripped = threshold.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
