package cloud.cholewa.blinds.model.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A schedule of the blinds, the body of a write and the answer of a read. A write replaces the
 * schedule whole, {@code enabled} included; {@code id}, {@code createdAt} and {@code updatedAt}
 * are the service's and are not read from a body. What the body has to say, and how its fields
 * go together, is {@code ScheduleRules}'. {@code position} belongs to the action {@code position}
 * alone.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Schedule(
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    Long id,
    String name,
    Boolean enabled,
    ScheduleDayType dayType,
    ScheduleWindow window,
    List<ScheduleTarget> targets,
    List<ScheduleCondition> conditions,
    ScheduleAction action,
    Integer position,
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    LocalDateTime createdAt,
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    LocalDateTime updatedAt
) {
}
