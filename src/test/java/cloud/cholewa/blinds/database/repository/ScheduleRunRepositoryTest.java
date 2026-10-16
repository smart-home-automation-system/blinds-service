package cloud.cholewa.blinds.database.repository;

import cloud.cholewa.blinds.database.model.ScheduleRunEntity;
import cloud.cholewa.blinds.engine.RunOutcome;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//The runs of the schedules on the table of V3, in a PostgreSQL of Testcontainers: once a day per
//schedule and blind whatever the engine remembers, the outcomes by the names of their constants, and
//gone with their schedule. Without a Docker it fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class ScheduleRunRepositoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final LocalDateTime FIVE_PM = DAY.atTime(17, 0, 3);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private ScheduleRunRepository sut;

    @Autowired
    private DatabaseClient databaseClient;

    private long scheduleId;

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
    void oneSchedule() {
        databaseClient.sql("DELETE FROM blinds_schedule").then().as(StepVerifier::create).verifyComplete();
        scheduleId = insertSchedule();
    }

    @Test
    void should_store_a_run_and_read_it_back_by_its_day() {
        sut.save(run(17, DAY, RunOutcome.MOVED)).as(StepVerifier::create).expectNextCount(1).verifyComplete();
        sut.save(run(52, DAY, RunOutcome.UNSUPPORTED)).as(StepVerifier::create).expectNextCount(1).verifyComplete();
        sut.save(run(17, DAY.minusDays(1), RunOutcome.FAILED)).as(StepVerifier::create).expectNextCount(1).verifyComplete();

        sut.findByFiredOn(DAY).collectList().as(StepVerifier::create)
            .assertNext(runs -> assertThat(runs)
                .extracting(ScheduleRunEntity::deviceId, ScheduleRunEntity::outcome, ScheduleRunEntity::firedAt)
                .containsExactlyInAnyOrder(
                    org.assertj.core.groups.Tuple.tuple(17L, RunOutcome.MOVED, FIVE_PM),
                    org.assertj.core.groups.Tuple.tuple(52L, RunOutcome.UNSUPPORTED, FIVE_PM)))
            .verifyComplete();
        sut.findByScheduleIdAndFiredOnOrderById(scheduleId, DAY).map(ScheduleRunEntity::deviceId).as(StepVerifier::create)
            .expectNext(17L, 52L)
            .verifyComplete();

        //the name of the constant, which the CHECK lists
        databaseClient.sql("SELECT outcome FROM blinds_schedule_run WHERE device_id = 52")
            .map(row -> row.get("outcome", String.class)).one()
            .as(StepVerifier::create).expectNext("UNSUPPORTED").verifyComplete();
    }

    @Test
    void should_refuse_a_second_run_of_a_schedule_for_a_blind_on_the_same_day() {
        sut.save(run(17, DAY, RunOutcome.MOVED)).as(StepVerifier::create).expectNextCount(1).verifyComplete();

        sut.save(run(17, DAY, RunOutcome.FAILED)).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
    }

    @Test
    void should_refuse_an_outcome_it_does_not_know() {
        databaseClient.sql("""
                INSERT INTO blinds_schedule_run (schedule_id, device_id, fired_on, fired_at, outcome)
                VALUES (:schedule, 17, DATE '2026-10-16', TIMESTAMP '2026-10-16 17:00:00', 'SKIPPED')
                """)
            .bind("schedule", scheduleId)
            .then()
            .as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
    }

    @Test
    void should_go_with_its_schedule() {
        sut.save(run(17, DAY, RunOutcome.MOVED)).as(StepVerifier::create).expectNextCount(1).verifyComplete();

        databaseClient.sql("DELETE FROM blinds_schedule WHERE id = :id").bind("id", scheduleId).then()
            .as(StepVerifier::create).verifyComplete();

        sut.count().as(StepVerifier::create).expectNext(0L).verifyComplete();
    }

    private ScheduleRunEntity run(final long deviceId, final LocalDate day, final RunOutcome outcome) {
        return new ScheduleRunEntity(null, scheduleId, deviceId, day, day.atTime(FIVE_PM.toLocalTime()), outcome);
    }

    private long insertSchedule() {
        return databaseClient.sql("""
                INSERT INTO blinds_schedule (name, enabled, day_type, window_from_kind, window_from_time,
                    window_to_kind, window_to_time, action, created_at, updated_at)
                VALUES ('Evening close', TRUE, 'ANY', 'CLOCK', TIME '16:00', 'CLOCK', TIME '18:00', 'CLOSE',
                    TIMESTAMP '2026-10-16 08:00:00', TIMESTAMP '2026-10-16 08:00:00')
                RETURNING id
                """)
            .map(row -> row.get("id", Long.class))
            .one()
            .blockOptional()
            .orElseThrow();
    }
}
