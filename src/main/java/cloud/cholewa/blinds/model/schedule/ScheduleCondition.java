package cloud.cholewa.blinds.model.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * What has to hold for a schedule to fire: a {@code metric} compared {@code lt} or {@code gt} with a
 * {@code threshold}, or {@code is} the truth {@code value}; and {@code holdMinutes} (0..120), how long
 * it has to hold without a break before it counts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScheduleCondition(
    ConditionMetric metric,
    ConditionOperator operator,
    BigDecimal threshold,
    Boolean value,
    Integer holdMinutes
) {

    public static ScheduleCondition compared(final ConditionMetric metric, final ConditionOperator operator,
        final BigDecimal threshold, final int holdMinutes) {
        return new ScheduleCondition(metric, operator, threshold, null, holdMinutes);
    }

    public static ScheduleCondition is(final ConditionMetric metric, final boolean value, final int holdMinutes) {
        return new ScheduleCondition(metric, ConditionOperator.IS, null, value, holdMinutes);
    }
}
