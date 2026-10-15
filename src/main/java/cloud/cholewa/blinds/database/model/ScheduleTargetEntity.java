package cloud.cholewa.blinds.database.model;

import cloud.cholewa.home.model.RoomName;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

//a room (the name of its constant) or one blind of the registry, never both
@Table("blinds_schedule_target")
public record ScheduleTargetEntity(
    @Id
    Long id,
    long scheduleId,
    RoomName room,
    Long deviceId
) {
}
