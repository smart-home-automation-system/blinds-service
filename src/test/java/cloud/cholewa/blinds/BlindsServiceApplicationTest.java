package cloud.cholewa.blinds;

import cloud.cholewa.commons.database.DatabaseProperties;
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

    //cholewa-commons >= 1.10.0 logs every call of a WebClient of the injected builder - the clients of
    //shelly-client included - from inside the reactive chain; a Logbook handler of the service's own
    //would log every call twice (HAS-301)
    @Test
    void should_leave_the_logging_of_the_calls_to_the_library() {
        assertThat(applicationContext.getBeanNamesForType(ShellyHttpClientCustomizer.class)).isEmpty();
    }

    //six scheduled tasks: with one thread a hung pass stopped all of them for 1.5 hours (2026-10-22);
    //the pool comes from cholewa-commons >= 1.11.0 (HAS-300, HAS-301)
    @Test
    void should_give_the_scheduled_tasks_a_pool() {
        assertThat(applicationContext.getEnvironment().getProperty("spring.task.scheduling.pool.size", Integer.class))
            .isEqualTo(10);
    }
}
