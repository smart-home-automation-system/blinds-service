package cloud.cholewa.blinds.database.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDateTime;

//the last reading of a window or door sensor (HAS-420, V6): updatedAt the time of its gateway, storedAt house time;
//written by WindowSensorReadingRepository.upsert, never by save()
@Table("window_sensor_reading")
public record WindowSensorReadingEntity(
    @Id Long sensorId,
    Boolean open,
    Instant updatedAt,
    LocalDateTime storedAt
) {
}
