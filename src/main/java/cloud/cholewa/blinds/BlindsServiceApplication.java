package cloud.cholewa.blinds;

import cloud.cholewa.blinds.config.EatonProperties;
import cloud.cholewa.blinds.config.InternalServiceProperties;
import cloud.cholewa.blinds.config.LocationProperties;
import cloud.cholewa.blinds.config.MonitorProperties;
import cloud.cholewa.blinds.config.NotificationConnectionProperties;
import cloud.cholewa.blinds.config.WindowProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    NotificationConnectionProperties.class,
    InternalServiceProperties.class,
    LocationProperties.class,
    EatonProperties.class,
    WindowProperties.class,
    MonitorProperties.class
})
public class BlindsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlindsServiceApplication.class, args);
    }
}
