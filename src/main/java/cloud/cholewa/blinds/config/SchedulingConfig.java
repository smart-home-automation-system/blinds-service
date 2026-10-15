package cloud.cholewa.blinds.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

//The schedules of the blinds run in the memory of this one instance - the deployment is Recreate
//and never scaled, two pods would move every blind twice. Off under the test profile: a
//@SpringBootTest context would otherwise run what the application schedules
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {
}
