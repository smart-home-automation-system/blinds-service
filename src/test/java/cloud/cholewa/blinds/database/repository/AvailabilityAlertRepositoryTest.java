package cloud.cholewa.blinds.database.repository;

import cloud.cholewa.blinds.database.model.AvailabilityAlertEntity;
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

//The open alerts of the availability monitor on the table of V5 (HAS-250), in a PostgreSQL of
//Testcontainers: an alert already open is kept as it was. Without a Docker it fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class AvailabilityAlertRepositoryTest {

    private static final LocalDateTime OUT = LocalDateTime.of(2026, 10, 21, 12, 0);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private AvailabilityAlertRepository sut;

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
        databaseClient.sql("DELETE FROM blinds_availability_alert").then().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_keep_an_alert_already_open_as_it_was() {
        sut.open(52, "Office window", "office", OUT, OUT.plusMinutes(5)).as(StepVerifier::create).expectNext(1).verifyComplete();
        sut.open(52, "Office window", "office", OUT.plusHours(1), OUT.plusHours(1)).as(StepVerifier::create).expectNext(0).verifyComplete();

        sut.findAll().as(StepVerifier::create)
            .expectNext(new AvailabilityAlertEntity(52, "Office window", "office", OUT, OUT.plusMinutes(5)))
            .verifyComplete();
    }

    @Test
    void should_move_the_last_alert_of_a_reminded_outage() {
        sut.open(52, "Office window", "office", OUT, OUT.plusMinutes(5)).as(StepVerifier::create).expectNext(1).verifyComplete();

        sut.reminded(52, OUT.plusHours(1).plusMinutes(5)).as(StepVerifier::create).expectNext(1).verifyComplete();
        sut.reminded(99, OUT).as(StepVerifier::create).expectNext(0).verifyComplete();

        sut.findById(52L).map(AvailabilityAlertEntity::lastAlertAt).as(StepVerifier::create)
            .expectNext(OUT.plusHours(1).plusMinutes(5))
            .verifyComplete();
    }

    @Test
    void should_close_an_alert() {
        sut.open(52, "Office window", "office", OUT, OUT).as(StepVerifier::create).expectNext(1).verifyComplete();

        sut.deleteById(52L).as(StepVerifier::create).verifyComplete();

        sut.findAll().as(StepVerifier::create).verifyComplete();
    }
}
