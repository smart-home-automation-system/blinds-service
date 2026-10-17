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

//application.yaml as the profiles read it (HAS-290)
class WindowPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_hold_the_state_of_a_window_sensor_for_half_an_hour_without_its_gateway() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(WindowProperties.class).gatewaySilentAfter())
                .isEqualTo(Duration.ofMinutes(30)));
    }

    //without the unit a bare number would bind as milliseconds: every window would be unknown at once
    @Test
    void should_read_a_bare_number_as_minutes() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "blinds.window.gateway-silent-after=45")
            .run(context -> assertThat(context.getBean(WindowProperties.class).gatewaySilentAfter())
                .isEqualTo(Duration.ofMinutes(45)));
    }

    //the gateways are read every five minutes: less than two reads would make a window unknown
    //after one missed read; more than a day would keep a dead gateway's windows for ever
    @ParameterizedTest
    @ValueSource(strings = {
        "blinds.window.gateway-silent-after=0",
        "blinds.window.gateway-silent-after=9",
        "blinds.window.gateway-silent-after=PT9M59S",
        "blinds.window.gateway-silent-after=PT24H1M",
        "blinds.window.gateway-silent-after="
    })
    void should_not_start_with_a_bound_out_of_its_range(final String silentAfter) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", silentAfter)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(WindowProperties.class)
    static class Properties {
    }
}
