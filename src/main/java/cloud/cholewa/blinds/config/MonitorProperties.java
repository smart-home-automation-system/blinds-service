package cloud.cholewa.blinds.config;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * The availability monitor of the Shelly blinds (HAS-250); a bare number is minutes.
 *
 * @param offlineAfter  how long a Shelly blind is unreachable before the household hears of it: a
 *                      Gen1 roller is polled every minute, so at least two failed polls
 * @param reminderEvery how often an outage that goes on is repeated
 */
@Validated
@ConfigurationProperties(prefix = "blinds.monitor")
public record MonitorProperties(
    @NotNull @DurationMin(minutes = 2) @DurationMax(hours = 2) @DurationUnit(ChronoUnit.MINUTES)
    Duration offlineAfter,
    @NotNull @DurationMin(minutes = 15) @DurationMax(hours = 24) @DurationUnit(ChronoUnit.MINUTES)
    Duration reminderEvery
) {
}
