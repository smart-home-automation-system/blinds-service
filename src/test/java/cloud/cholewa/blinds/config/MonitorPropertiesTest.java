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

//application.yaml as the profiles read it (HAS-250)
class MonitorPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_alert_after_five_minutes_and_remind_every_hour() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> {
                final MonitorProperties properties = context.getBean(MonitorProperties.class);
                assertThat(properties.offlineAfter()).isEqualTo(Duration.ofMinutes(5));
                assertThat(properties.reminderEvery()).isEqualTo(Duration.ofHours(1));
            });
    }

    //without the unit a bare number would bind as milliseconds: an alert after one failed poll
    @Test
    void should_read_a_bare_number_as_minutes() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "blinds.monitor.offline-after=10", "blinds.monitor.reminder-every=30")
            .run(context -> {
                final MonitorProperties properties = context.getBean(MonitorProperties.class);
                assertThat(properties.offlineAfter()).isEqualTo(Duration.ofMinutes(10));
                assertThat(properties.reminderEvery()).isEqualTo(Duration.ofMinutes(30));
            });
    }

    //a Gen1 roller is polled every minute: one failed poll is no outage
    @ParameterizedTest
    @ValueSource(strings = {
        "blinds.monitor.offline-after=1",
        "blinds.monitor.offline-after=3h",
        "blinds.monitor.offline-after=",
        "blinds.monitor.reminder-every=14",
        "blinds.monitor.reminder-every=25h",
        "blinds.monitor.reminder-every="
    })
    void should_not_start_with_a_bound_out_of_range(final String property) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", property)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(MonitorProperties.class)
    static class Properties {
    }
}
