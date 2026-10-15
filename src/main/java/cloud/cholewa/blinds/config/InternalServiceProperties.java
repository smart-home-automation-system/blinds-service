package cloud.cholewa.blinds.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * The services this one calls inside the cluster, over k8s DNS.<br>
 * {@code database} - database-service, the device registry.<br>
 * {@code weather} - weather-service.<br>
 * {@code amx} - amx-service, through which the Eaton blinds are moved.
 */
@Validated
@ConfigurationProperties(prefix = "internal.service")
public record InternalServiceProperties(
    @NotNull @Valid Endpoint database,
    @NotNull @Valid Endpoint weather,
    @NotNull @Valid Endpoint amx
) {

    /**
     * {@code url} - scheme, host and port; the paths, base path of the service included, belong
     * to the client.<br>
     * {@code connectTimeout} - how long the service may take to accept the connection.<br>
     * {@code responseTimeout} - the longest silence while its answer is awaited and read.<br>
     * A bare number is seconds: without the unit it would bind as milliseconds and every call would
     * time out. Zero would switch the timeout off, which is the unbounded wait these exist to end,
     * and so would a value meant as milliseconds ("5000") - hence the upper bound.
     */
    public record Endpoint(
        @NotBlank String url,
        @NotNull @DurationMin(seconds = 1) @DurationMax(seconds = 60) @DurationUnit(ChronoUnit.SECONDS)
        Duration connectTimeout,
        @NotNull @DurationMin(seconds = 1) @DurationMax(seconds = 60) @DurationUnit(ChronoUnit.SECONDS)
        Duration responseTimeout
    ) {
    }
}
