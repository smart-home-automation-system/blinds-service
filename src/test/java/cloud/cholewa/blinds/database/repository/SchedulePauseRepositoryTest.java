package cloud.cholewa.blinds.database.repository;

import cloud.cholewa.blinds.database.model.SchedulePauseEntity;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

//The pauses of the blinds on the table of V4 (HAS-232), in a PostgreSQL of Testcontainers: a second
//pause keeps the first, as the dashboard may send it twice. Without a Docker it fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class SchedulePauseRepositoryTest {

    private static final LocalDateTime EVENING = LocalDateTime.of(2026, 10, 17, 20, 15);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private SchedulePauseRepository sut;

    @Autowired
    private DatabaseClient databaseClient;

    @DynamicPropertySource
    static void connectToTheContainer(final DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
            POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
    }

    @BeforeEach
    void empty() {
        databaseClient.sql("DELETE FROM blinds_schedule_pause").then().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_keep_the_first_pause_of_a_blind() {
        sut.pause(17, EVENING).as(StepVerifier::create).expectNext(1).verifyComplete();
        sut.pause(17, EVENING.plusHours(1)).as(StepVerifier::create).expectNext(0).verifyComplete();

        sut.findAll().as(StepVerifier::create)
            .expectNext(new SchedulePauseEntity(17, EVENING))
            .verifyComplete();
    }

    @Test
    void should_end_a_pause() {
        sut.pause(17, EVENING).as(StepVerifier::create).expectNext(1).verifyComplete();
        sut.pause(52, EVENING).as(StepVerifier::create).expectNext(1).verifyComplete();

        sut.deleteById(17L).as(StepVerifier::create).verifyComplete();
        //a blind that is not paused: nothing to end
        sut.deleteById(99L).as(StepVerifier::create).verifyComplete();

        sut.findAll().map(SchedulePauseEntity::deviceId).as(StepVerifier::create).expectNext(52L).verifyComplete();
    }
}
