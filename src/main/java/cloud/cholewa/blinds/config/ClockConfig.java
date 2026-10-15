package cloud.cholewa.blinds.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

//the time of the house - the zone of the JVM, as LocalDateTime.now(ZoneId.systemDefault()) everywhere
//else; a bean so that a test can move it
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
