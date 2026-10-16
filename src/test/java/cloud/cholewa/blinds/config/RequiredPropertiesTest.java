package cloud.cholewa.blinds.config;

import cloud.cholewa.commons.startup.RequiredPropertiesAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

//The two passwords of the broker and the place of the house have no default in the shared document,
//and the list of cholewa.required-properties makes a pod without one of them refuse to start
//(cholewa-commons). This ties the list to the placeholders of application.yaml, profile by profile.
//The active profiles are given here, above the system property surefire sets for every test class
class RequiredPropertiesTest {

    private static final String[] LOCATION = {"location-latitude=50.1", "location-longitude=19.6"};

    //without the variables of the machine: a developer who exported a password for a local run would
    //otherwise see the tests of a missing one fail
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(RequiredPropertiesAutoConfiguration.class));

    @Test
    void should_not_start_in_the_cluster_without_the_passwords_and_the_place_of_the_house() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getStartupFailure()).hasStackTraceContaining(
                "A required property has no value: spring.rabbitmq.password (set rabbitmq-password), "
                    + "notification.password (set notification-rabbitmq-password), "
                    + "blinds.location.latitude (set location-latitude), "
                    + "blinds.location.longitude (set location-longitude)"));
    }

    @ParameterizedTest
    @CsvSource({
        "notification-rabbitmq-password, spring.rabbitmq.password (set rabbitmq-password)",
        "rabbitmq-password, notification.password (set notification-rabbitmq-password)"
    })
    void should_not_start_in_the_cluster_without_one_of_the_passwords(
        final String given, final String missing) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", given + "=secret")
            .withPropertyValues(LOCATION)
            .run(context -> assertThat(context.getStartupFailure())
                .hasMessage("A required property has no value: " + missing));
    }

    @Test
    void should_not_start_in_the_cluster_without_the_place_of_the_house() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "rabbitmq-password=a", "notification-rabbitmq-password=b",
                "location-latitude=50.1")
            .run(context -> assertThat(context.getStartupFailure())
                .hasMessage("A required property has no value: blinds.location.longitude (set location-longitude)"));
    }

    @Test
    void should_start_in_the_cluster_with_the_values_of_the_deployment() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=home",
                "rabbitmq-password=listenerSecret",
                "notification-rabbitmq-password=publisherSecret"
            )
            .withPropertyValues(LOCATION)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getEnvironment().getProperty("spring.rabbitmq.password")).isEqualTo("listenerSecret");
                assertThat(context.getEnvironment().getProperty("notification.password")).isEqualTo("publisherSecret");
                assertThat(context.getEnvironment().getProperty("blinds.location.latitude")).isEqualTo("50.1");
                assertThat(context.getEnvironment().getProperty("blinds.location.longitude")).isEqualTo("19.6");
            });
    }

    //a run on a workstation neither consumes nor publishes unless it is given the real passwords; the
    //place of the house it needs all the same - the schedules run there too
    @Test
    void should_start_on_a_workstation_without_the_passwords() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home,local")
            .withPropertyValues(LOCATION)
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void should_start_under_the_test_profile() {
        contextRunner
            .withPropertyValues("spring.profiles.active=test")
            .run(context -> assertThat(context).hasNotFailed());
    }
}
