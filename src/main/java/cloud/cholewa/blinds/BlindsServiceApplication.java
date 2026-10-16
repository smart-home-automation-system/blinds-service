package cloud.cholewa.blinds;

import cloud.cholewa.blinds.config.InternalServiceProperties;
import cloud.cholewa.blinds.config.LocationProperties;
import cloud.cholewa.blinds.config.NotificationConnectionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    NotificationConnectionProperties.class,
    InternalServiceProperties.class,
    LocationProperties.class
})
public class BlindsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlindsServiceApplication.class, args);
    }
}
