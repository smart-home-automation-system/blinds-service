package cloud.cholewa.blinds.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where the house is, for the sunrise and the sunset of the schedules: degrees, north and east
 * positive. A private fact - the manifest gives it, the public configuration does not hold it.
 */
@Validated
@ConfigurationProperties(prefix = "blinds.location")
public record LocationProperties(
    @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
    @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude
) {
}
