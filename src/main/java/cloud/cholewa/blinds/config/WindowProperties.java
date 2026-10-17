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
 * The window sensors of the blinds (HAS-290).
 *
 * @param gatewaySilentAfter how long the state of a sensor stands without its gateway confirming
 *                           it - by publishing the sensor or by answering the read of every five
 *                           minutes - before the window is unknown; a bare number is minutes. A
 *                           Shelly BLU Door/Window sends only when it changes (no beacon), so the
 *                           silence of the sensor itself proves nothing (owner, 2026-10-17); the
 *                           gateway that stopped answering does. At least two reads of the gateway
 */
@Validated
@ConfigurationProperties(prefix = "blinds.window")
public record WindowProperties(
    @NotNull @DurationMin(minutes = 10) @DurationMax(hours = 24) @DurationUnit(ChronoUnit.MINUTES)
    Duration gatewaySilentAfter
) {
}
