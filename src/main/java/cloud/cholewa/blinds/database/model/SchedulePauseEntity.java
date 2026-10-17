package cloud.cholewa.blinds.database.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//the schedules of one blind paused since pausedAt, until the next run the pause prevents (V4)
@Table("blinds_schedule_pause")
public record SchedulePauseEntity(
    @Id
    long deviceId,
    LocalDateTime pausedAt
) {
}
