package cloud.cholewa.blinds.database.repository;

import cloud.cholewa.blinds.database.model.ScheduleTargetEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

//the targets of a schedule in the order they were given, which is the order of their ids
public interface ScheduleTargetRepository extends R2dbcRepository<ScheduleTargetEntity, Long> {

    Flux<ScheduleTargetEntity> findByScheduleIdOrderById(long scheduleId);

    Flux<ScheduleTargetEntity> findAllByOrderById();

    @Modifying
    @Query("DELETE FROM blinds_schedule_target WHERE schedule_id = :scheduleId")
    Mono<Integer> deleteByScheduleId(long scheduleId);
}
