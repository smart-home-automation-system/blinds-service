package cloud.cholewa.blinds.database.model;

import cloud.cholewa.blinds.model.BlindState;
import cloud.cholewa.blinds.model.Direction;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("blinds_state")
public record BlindStateEntity(
    @Id
    Long id,
    long deviceId,
    BlindState state,
    Integer position,
    Direction lastDirection,
    String source,
    LocalDateTime startedAt,
    LocalDateTime lastCheckedAt
) {
}
