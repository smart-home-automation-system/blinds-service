package cloud.cholewa.blinds.database.model;

import cloud.cholewa.blinds.model.schedule.ScheduleAction;
import cloud.cholewa.blinds.model.schedule.ScheduleDayType;
import cloud.cholewa.blinds.model.schedule.WindowKind;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.time.LocalTime;

//the enums are written by the names of their constants, which is what the CHECKs of V2 list
@Table("blinds_schedule")
public record ScheduleEntity(
    @Id
    Long id,
    String name,
    boolean enabled,
    ScheduleDayType dayType,
    WindowKind windowFromKind,
    LocalTime windowFromTime,
    Integer windowFromOffsetMin,
    LocalTime windowFromEarliest,
    LocalTime windowFromLatest,
    WindowKind windowToKind,
    LocalTime windowToTime,
    Integer windowToOffsetMin,
    LocalTime windowToEarliest,
    LocalTime windowToLatest,
    ScheduleAction action,
    Integer position,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

    public ScheduleEntity withEnabled(final boolean enabled, final LocalDateTime updatedAt) {
        return new ScheduleEntity(id, name, enabled, dayType, windowFromKind, windowFromTime, windowFromOffsetMin,
            windowFromEarliest, windowFromLatest, windowToKind, windowToTime, windowToOffsetMin, windowToEarliest,
            windowToLatest, action, position, createdAt, updatedAt);
    }
}
