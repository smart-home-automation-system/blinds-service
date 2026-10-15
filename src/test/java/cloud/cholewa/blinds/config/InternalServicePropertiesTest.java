package cloud.cholewa.blinds.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

//application.yaml as the profiles read it; the active profiles are given here, above the system
//property surefire sets for every test class
class InternalServicePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_call_the_services_over_the_dns_of_the_cluster() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> {
                final InternalServiceProperties properties = context.getBean(InternalServiceProperties.class);

                assertThat(properties.database().url()).isEqualTo("http://database-service:6200");
                assertThat(properties.weather().url()).isEqualTo("http://weather-service:6200");
                assertThat(properties.amx().url()).isEqualTo("http://amx-service:6200");
                assertThat(properties.database().connectTimeout()).isEqualTo(Duration.ofSeconds(3));
                assertThat(properties.database().responseTimeout()).isEqualTo(Duration.ofSeconds(5));
            });
    }

    @Test
    void should_call_the_local_ports_of_the_services_on_a_workstation() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home,local")
            .run(context -> {
                final InternalServiceProperties properties = context.getBean(InternalServiceProperties.class);

                assertThat(properties.database().url()).isEqualTo("http://localhost:6005");
                assertThat(properties.weather().url()).isEqualTo("http://localhost:6010");
                assertThat(properties.amx().url()).isEqualTo("http://localhost:6001");
                //the timeouts of the shared document stay
                assertThat(properties.amx().responseTimeout()).isEqualTo(Duration.ofSeconds(10));
            });
    }

    //a context of a test must not reach a service even where its name resolves
    @Test
    void should_point_every_service_at_a_dead_port_under_the_test_profile() {
        contextRunner
            .withPropertyValues("spring.profiles.active=test")
            .run(context -> {
                final InternalServiceProperties properties = context.getBean(InternalServiceProperties.class);

                assertThat(properties.database().url()).isEqualTo("http://localhost:1");
                assertThat(properties.weather().url()).isEqualTo("http://localhost:1");
                assertThat(properties.amx().url()).isEqualTo("http://localhost:1");
            });
    }

    //without the unit a bare number would bind as milliseconds and every call would time out
    @Test
    void should_read_a_bare_number_as_seconds() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=home",
                "internal.service.weather.connect-timeout=2",
                "internal.service.weather.response-timeout=7"
            )
            .run(context -> {
                final InternalServiceProperties properties = context.getBean(InternalServiceProperties.class);

                assertThat(properties.weather().connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                assertThat(properties.weather().responseTimeout()).isEqualTo(Duration.ofSeconds(7));
            });
    }

    //zero switches the timeout off, a value meant as milliseconds is more than an hour
    @ParameterizedTest
    @ValueSource(strings = {
        "internal.service.database.connect-timeout=0",
        "internal.service.database.response-timeout=500ms",
        "internal.service.amx.response-timeout=5000",
        "internal.service.weather.connect-timeout=61s"
    })
    void should_not_start_with_a_timeout_out_of_bounds(final String timeout) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", timeout)
            .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"internal.service.database.url=", "internal.service.amx.url=  "})
    void should_not_start_without_the_address_of_a_service(final String url) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", url)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(InternalServiceProperties.class)
    static class Properties {
    }
}
