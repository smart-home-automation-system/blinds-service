package cloud.cholewa.blinds.database.model;

import cloud.cholewa.blinds.model.schedule.ConditionMetric;
import cloud.cholewa.blinds.model.schedule.ConditionOperator;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;

//threshold for LT and GT, expected for IS - the value of the API is a keyword in SQL
@Table("blinds_schedule_condition")
public record ScheduleConditionEntity(
    @Id
    Long id,
    long scheduleId,
    ConditionMetric metric,
    ConditionOperator operator,
    BigDecimal threshold,
    Boolean expected,
    int holdMinutes
) {
}
