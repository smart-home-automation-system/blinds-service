package cloud.cholewa.blinds.database.model;

import cloud.cholewa.blinds.engine.RunOutcome;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

//one blind moved by one schedule on one day; the outcome by the name of its constant (V3)
@Table("blinds_schedule_run")
public record ScheduleRunEntity(
    @Id
    Long id,
    long scheduleId,
    long deviceId,
    LocalDate firedOn,
    LocalDateTime firedAt,
    RunOutcome outcome
) {
}
