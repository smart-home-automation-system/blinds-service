package cloud.cholewa.blinds.engine;

import cloud.cholewa.blinds.engine.WeatherNow.Observation;
import cloud.cholewa.blinds.model.schedule.ConditionMetric;
import cloud.cholewa.commons.error.DownstreamErrors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

//The current weather of weather-service (GET /home/weather/current, HAS-238): every metric by its
//name, with the value, when the station last had it and whether weather-service holds it stale.
//Only the four metrics a condition can name are kept. No body, or one that is not a map, is a failed
//call; what a failing weather-service says goes to the log
@Slf4j
@Component
public class WeatherClient {

    private static final ParameterizedTypeReference<Map<String, CurrentMetric>> CURRENT = new ParameterizedTypeReference<>() {
    };

    //the names of the read API of weather-service - its wire contract (Metric.apiName there)
    static final Map<String, ConditionMetric> METRICS = Map.of(
        "illuminance", ConditionMetric.ILLUMINANCE,
        "temperature", ConditionMetric.OUTSIDE_TEMPERATURE,
        "gust", ConditionMetric.WIND_GUST,
        "raining", ConditionMetric.RAINING
    );

    private final WebClient weatherWebClient;

    public WeatherClient(@Qualifier("weatherWebClient") final WebClient weatherWebClient) {
        this.weatherWebClient = weatherWebClient;
    }

    public Mono<Map<ConditionMetric, Observation>> current() {
        return weatherWebClient
            .get()
            .uri("/home/weather/current")
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
                .map(error -> {
                    log.warn("weather-service answered {} to the read of the current weather: {}",
                        error.status().value(), error.errors());
                    return new InputCallException("weather-service answered " + error.status().value());
                }))
            .bodyToMono(CURRENT)
            .switchIfEmpty(Mono.error(() -> new InputCallException("weather-service answered without the weather")))
            .map(WeatherClient::toObservations);
    }

    private static Map<ConditionMetric, Observation> toObservations(final Map<String, CurrentMetric> current) {
        final Map<ConditionMetric, Observation> observations = new EnumMap<>(ConditionMetric.class);
        METRICS.forEach((name, metric) -> {
            final CurrentMetric reading = current.get(name);
            if (reading != null && reading.value() != null && reading.measuredAt() != null) {
                observations.put(metric, new Observation(reading.value(), reading.measuredAt(),
                    Boolean.TRUE.equals(reading.stale())));
            }
        });
        return observations;
    }

    //one metric of the answer, as weather-service writes it
    record CurrentMetric(Object value, LocalDateTime measuredAt, Boolean stale) {
    }
}
