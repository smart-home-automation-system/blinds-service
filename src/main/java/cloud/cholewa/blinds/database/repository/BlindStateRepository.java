package cloud.cholewa.blinds.database.repository;

import cloud.cholewa.blinds.database.model.BlindStateEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface BlindStateRepository extends R2dbcRepository<BlindStateEntity, Long> {

    //the latest row of every blind - the state the service starts from. "Latest" is the highest
    //id, not the latest started_at: rows are inserted in the order the states changed, while
    //started_at is local time and runs backwards on the night the clocks go back
    @Query("""
        SELECT DISTINCT ON (device_id) *
        FROM blinds_state
        ORDER BY device_id, id DESC
        """)
    Flux<BlindStateEntity> findLatestPerDevice();

    //a reading that confirms the state: moves last_checked_at of the latest row of the blind and
    //writes the position it knows now (a moving blind reports one, a Gen1 action does not); answers
    //the number of rows changed, 0 when the blind has no row yet
    @Modifying
    @Query("""
        UPDATE blinds_state
        SET last_checked_at = :checkedAt,
            position        = :position
        WHERE id = (SELECT id
                    FROM blinds_state
                    WHERE device_id = :deviceId
                    ORDER BY id DESC
                    LIMIT 1)
        """)
    Mono<Integer> touchLatest(long deviceId, LocalDateTime checkedAt, Integer position);
}
