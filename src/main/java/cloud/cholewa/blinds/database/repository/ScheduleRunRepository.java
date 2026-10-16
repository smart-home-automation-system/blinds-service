package cloud.cholewa.blinds.database.repository;

import cloud.cholewa.blinds.database.model.ScheduleRunEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.time.LocalDate;

public interface ScheduleRunRepository extends R2dbcRepository<ScheduleRunEntity, Long> {

    Flux<ScheduleRunEntity> findByFiredOn(LocalDate firedOn);

    Flux<ScheduleRunEntity> findByScheduleIdAndFiredOnOrderById(long scheduleId, LocalDate firedOn);
}
