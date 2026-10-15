package cloud.cholewa.blinds.database.repository;

import cloud.cholewa.blinds.database.model.ScheduleConditionEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

//the conditions of a schedule in the order they were given, which is the order of their ids
public interface ScheduleConditionRepository extends R2dbcRepository<ScheduleConditionEntity, Long> {

    Flux<ScheduleConditionEntity> findByScheduleIdOrderById(long scheduleId);

    Flux<ScheduleConditionEntity> findAllByOrderById();

    @Modifying
    @Query("DELETE FROM blinds_schedule_condition WHERE schedule_id = :scheduleId")
    Mono<Integer> deleteByScheduleId(long scheduleId);
}
