package cloud.cholewa.blinds.database.repository;

import cloud.cholewa.blinds.database.model.AvailabilityAlertEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface AvailabilityAlertRepository extends R2dbcRepository<AvailabilityAlertEntity, Long> {

    //an alert already open stays as it was: its start and its last notification are the household's
    @Modifying
    @Query("""
        INSERT INTO blinds_availability_alert (device_id, name, room, since, last_alert_at)
        VALUES (:deviceId, :name, :room, :since, :lastAlertAt)
        ON CONFLICT (device_id) DO NOTHING""")
    Mono<Integer> open(long deviceId, String name, String room, LocalDateTime since, LocalDateTime lastAlertAt);

    @Modifying
    @Query("UPDATE blinds_availability_alert SET last_alert_at = :at WHERE device_id = :deviceId")
    Mono<Integer> reminded(long deviceId, LocalDateTime at);
}
