package cloud.cholewa.blinds.database.repository;

import cloud.cholewa.blinds.database.model.ScheduleEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

public interface ScheduleRepository extends R2dbcRepository<ScheduleEntity, Long> {
}
