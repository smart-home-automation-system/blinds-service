package cloud.cholewa.blinds.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

//Every address that leads out of the JVM, as the test profile reads it: a context of a test must
//reach nothing even where the names resolve. The Shelly devices have no address of their own here -
//they come from the device registry, which is read from database-service, itself on a dead port.
//A new outward address gets its line here
class ApplicationYamlTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void should_point_everything_outward_at_a_dead_port_under_the_test_profile() {
        contextRunner
            .withPropertyValues("spring.profiles.active=test")
            .run(context -> {
                final Environment environment = context.getEnvironment();

                assertThat(environment.getProperty("spring.rabbitmq.host")).isEqualTo("localhost");
                assertThat(environment.getProperty("spring.rabbitmq.port")).isEqualTo("1");
                assertThat(environment.getProperty("database.host")).isEqualTo("localhost");
                assertThat(environment.getProperty("database.port")).isEqualTo("1");
                assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false");
                assertThat(environment.getProperty("internal.service.database.url")).isEqualTo("http://localhost:1");
                assertThat(environment.getProperty("internal.service.weather.url")).isEqualTo("http://localhost:1");
                assertThat(environment.getProperty("internal.service.amx.url")).isEqualTo("http://localhost:1");
                //"test" matches no binding of the notification exchange
                assertThat(environment.getProperty("notification.env")).isEqualTo("test");
            });
    }
}
