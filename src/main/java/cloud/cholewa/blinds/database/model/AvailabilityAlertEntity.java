package cloud.cholewa.blinds.database.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//a Shelly blind the household was told does not answer (V5); inserted by AvailabilityAlertRepository.open,
//never by save(), which takes a row with its key set for an update
@Table("blinds_availability_alert")
public record AvailabilityAlertEntity(
    @Id
    long deviceId,
    String name,
    String room,
    LocalDateTime since,
    LocalDateTime lastAlertAt
) {
}
