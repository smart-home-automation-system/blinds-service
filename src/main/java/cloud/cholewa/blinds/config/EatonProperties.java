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
 * The Eaton blinds (HAS-291).
 *
 * @param confirmAfter how long after an open or a close the 70 status of the movement is waited
 *                     for before the actuator is asked for its status - a bare number is seconds.
 *                     Asked after the end of a run the actuator only says it stopped, which tells
 *                     no direction: so within seconds, and well inside the two minutes a command
 *                     is attributed for
 */
@Validated
@ConfigurationProperties(prefix = "blinds.eaton")
public record EatonProperties(
    @NotNull @DurationMin(seconds = 1) @DurationMax(seconds = 60) @DurationUnit(ChronoUnit.SECONDS)
    Duration confirmAfter
) {
}
