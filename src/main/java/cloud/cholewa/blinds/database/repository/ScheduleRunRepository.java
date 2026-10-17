package cloud.cholewa.blinds.database.repository;

import cloud.cholewa.blinds.database.model.ScheduleRunEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.Collection;

public interface ScheduleRunRepository extends R2dbcRepository<ScheduleRunEntity, Long> {

    Flux<ScheduleRunEntity> findByFiredOn(LocalDate firedOn);

    //the runs of the windows that opened on these days - yesterday and today for a night window
    Flux<ScheduleRunEntity> findByFiredOnIn(Collection<LocalDate> firedOn);

    Flux<ScheduleRunEntity> findByScheduleIdAndFiredOnOrderById(long scheduleId, LocalDate firedOn);
}
