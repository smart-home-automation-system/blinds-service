package cloud.cholewa.blinds.database.repository;

import cloud.cholewa.blinds.database.model.BlindStateEntity;
import cloud.cholewa.blinds.model.BlindState;
import cloud.cholewa.blinds.model.Direction;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//The SQL of the state is PostgreSQL's own (DISTINCT ON), so it runs against a PostgreSQL in Docker
//(Testcontainers), on the table the migration of the service creates. The slice builds a plain
//connection from spring.r2dbc.*, not the pooled one of cholewa-commons, which is why the container
//needs no SSL here. Without a Docker the test fails, it is not skipped
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class BlindStateRepositoryTest {

    private static final LocalDateTime MORNING = LocalDateTime.of(2026, 10, 15, 7, 30);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private BlindStateRepository sut;

    @DynamicPropertySource
    static void connectToTheContainer(final DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
            POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    //the test profile switches Flyway off and the slice would not run it anyway
    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
    }

    @BeforeEach
    void forgetTheStates() {
        sut.deleteAll().as(StepVerifier::create).verifyComplete();
    }

    //the latest is the highest id, not the latest start: the night the clocks go back a later row
    //may start earlier
    @Test
    void should_read_the_latest_row_of_every_blind() {
        save(53, BlindState.CLOSED, 0, MORNING);
        save(17, BlindState.STOPPED, null, MORNING);
        save(53, BlindState.OPENING, 20, MORNING.plusMinutes(5));
        save(53, BlindState.OPEN, 100, MORNING.minusHours(1));

        sut.findLatestPerDevice().collectList().as(StepVerifier::create)
            .assertNext(rows -> {
                assertThat(rows).extracting(BlindStateEntity::deviceId).containsExactly(17L, 53L);
                assertThat(rows.get(0).position()).isNull();
                assertThat(rows.get(0).lastDirection()).isNull();
                assertThat(rows.get(1).state()).isEqualTo(BlindState.OPEN);
                assertThat(rows.get(1).lastDirection()).isEqualTo(Direction.OPEN);
            })
            .verifyComplete();
    }

    @Test
    void should_touch_the_latest_row_of_the_blind_only() {
        final long older = save(53, BlindState.CLOSED, 0, MORNING);
        final long latest = save(53, BlindState.STOPPED, null, MORNING.plusMinutes(5));
        final long other = save(17, BlindState.STOPPED, null, MORNING.plusMinutes(5));

        sut.touchLatest(53, MORNING.plusMinutes(9), 37).as(StepVerifier::create).expectNext(1).verifyComplete();

        sut.findById(latest).as(StepVerifier::create)
            .assertNext(row -> {
                assertThat(row.lastCheckedAt()).isEqualTo(MORNING.plusMinutes(9));
                assertThat(row.position()).isEqualTo(37);
                assertThat(row.startedAt()).isEqualTo(MORNING.plusMinutes(5));
            })
            .verifyComplete();
        sut.findById(older).map(BlindStateEntity::lastCheckedAt).as(StepVerifier::create)
            .expectNext(MORNING).verifyComplete();
        sut.findById(other).map(BlindStateEntity::lastCheckedAt).as(StepVerifier::create)
            .expectNext(MORNING.plusMinutes(5)).verifyComplete();
    }

    //a blind that does not report a position: the parameter is bound as a NULL of its type
    @Test
    void should_touch_without_a_position() {
        final long latest = save(17, BlindState.STOPPED, null, MORNING);

        sut.touchLatest(17, MORNING.plusMinutes(1), null).as(StepVerifier::create).expectNext(1).verifyComplete();

        sut.findById(latest).map(row -> row.position() == null).as(StepVerifier::create).expectNext(true).verifyComplete();
    }

    @Test
    void should_touch_nothing_for_a_blind_without_a_row() {
        sut.touchLatest(99, MORNING, 0).as(StepVerifier::create).expectNext(0).verifyComplete();
    }

    @Test
    void should_refuse_a_position_out_of_range() {
        sut.save(new BlindStateEntity(null, 53, BlindState.STOPPED, 101, null, "external", MORNING, MORNING))
            .as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
    }

    private long save(final long deviceId, final BlindState state, final Integer position, final LocalDateTime at) {
        final Direction direction = state == BlindState.OPEN || state == BlindState.OPENING ? Direction.OPEN
            : position == null ? null : Direction.CLOSE;
        return sut.save(new BlindStateEntity(null, deviceId, state, position, direction, "external", at, at))
            .map(BlindStateEntity::id)
            .block();
    }
}
