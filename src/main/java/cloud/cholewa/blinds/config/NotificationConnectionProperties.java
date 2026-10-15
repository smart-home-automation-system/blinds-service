package cloud.cholewa.blinds.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The second connection to the broker, the one the notifications are published over, and where
 * they go. Host and port are those of {@code spring.rabbitmq.*} - it is the same broker, only
 * another virtual host.<br>
 * {@code env} - the value of the {@code env} header the exchange routes by: {@code prod} or {@code dev}.
 * <p>
 * Not the {@code cholewa.notification.*} group of {@code cholewa-commons}: that one configures the
 * publisher over the connection of the application and knows no virtual host, user or password.
 */
@Validated
@ConfigurationProperties(prefix = "notification")
public record NotificationConnectionProperties(
    @NotBlank String virtualHost,
    @NotBlank String username,
    @NotBlank String password,
    @NotBlank String exchange,
    @NotBlank String env
) {
}
