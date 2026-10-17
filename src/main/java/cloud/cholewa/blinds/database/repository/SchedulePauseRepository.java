package cloud.cholewa.blinds.database.repository;

import cloud.cholewa.blinds.database.model.SchedulePauseEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface SchedulePauseRepository extends R2dbcRepository<SchedulePauseEntity, Long> {

    //a second pause of a paused blind keeps the first: the pause is the same, and so is its start.
    //save() would not do - with the id set it updates, and a row that is not there is an error
    @Modifying
    @Query("INSERT INTO blinds_schedule_pause (device_id, paused_at) VALUES (:deviceId, :pausedAt) ON CONFLICT (device_id) DO NOTHING")
    Mono<Integer> pause(long deviceId, LocalDateTime pausedAt);
}
