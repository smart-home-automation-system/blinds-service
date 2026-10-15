package cloud.cholewa.blinds;

import cloud.cholewa.commons.database.DatabaseProperties;
import cloud.cholewa.shelly.http.ShellyHttpClientCustomizer;
import cloud.cholewa.shelly.http.ShellyHttpClientCustomizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class BlindsServiceApplicationTest {

    @Autowired
    private DatabaseProperties databaseProperties;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
    }

    //the pool is this service's share of the 22 connections of the managed database; a lost or
    //mistyped key falls back to the library default without a sound
    @Test
    void should_keep_the_connection_pool_at_its_share_of_the_database_budget() {
        assertThat(databaseProperties.pool().maxSize()).isEqualTo(2);
    }

    //a context of a test must not run what the application schedules
    @Test
    void should_schedule_nothing_under_the_test_profile() {
        assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    //the Shelly calls of the library reach the log only through the customizer
    @Test
    void should_put_the_shelly_calls_into_the_log() {
        assertThat(applicationContext.getBeanNamesForType(ShellyHttpClientCustomizer.class))
            .containsExactly("shellyCallsInTheLog");
    }
}
