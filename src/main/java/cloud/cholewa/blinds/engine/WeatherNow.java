package cloud.cholewa.blinds.engine;

import cloud.cholewa.blinds.model.schedule.ConditionMetric;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * The weather as last read from weather-service, by the metric of a condition. A metric the
 * station never sent, or the read did not bring, is not in the map. {@code readAt} is when the
 * read was made, {@code null} for a read that failed.
 */
public record WeatherNow(Map<ConditionMetric, Observation> metrics, LocalDateTime readAt) {

    public static WeatherNow unknown() {
        return new WeatherNow(Map.of(), null);
    }

    /**
     * One metric: a number, or {@code true}/{@code false} for {@code raining}; {@code measuredAt}
     * the newest time the station is known to have had it; {@code stale} as weather-service judged it.
     */
    public record Observation(Object value, LocalDateTime measuredAt, boolean stale) {
    }
}
