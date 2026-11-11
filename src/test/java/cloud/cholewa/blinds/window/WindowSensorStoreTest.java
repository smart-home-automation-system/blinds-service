package cloud.cholewa.blinds.window;

import cloud.cholewa.blinds.config.ClockConfig;
import cloud.cholewa.blinds.database.repository.WindowSensorReadingRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

//HAS-420, the last reading of every window sensor in window_sensor_reading (V6), in a PostgreSQL of Testcontainers:
//the upsert is PostgreSQL's own. A row per sensor, a reading its gateway heard earlier never replaces a later one, and
//a read back is never confirmed. Without a Docker it fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import({WindowSensorStore.class, ClockConfig.class})
class WindowSensorStoreTest {

    private static final Instant HEARD = Instant.parse("2026-11-10T07:23:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private WindowSensorStore sut;

    @Autowired
    private WindowSensorReadingRepository repository;

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
    void forgetTheReadings() {
        repository.deleteAll().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_read_back_what_was_stored_as_not_confirmed() {
        save(67, true, HEARD);
        save(68, false, null);

        assertThat(loaded()).containsExactlyInAnyOrderEntriesOf(Map.of(
            67L, new WindowSensors.Reading(true, HEARD, null),
            68L, new WindowSensors.Reading(false, null, null)));
    }

    //one row per sensor: a later reading replaces it
    @Test
    void should_replace_the_reading_of_a_sensor() {
        save(67, true, HEARD);
        save(67, false, HEARD.plusSeconds(2));

        assertThat(loaded()).containsExactlyEntriesOf(Map.of(67L, new WindowSensors.Reading(false, HEARD.plusSeconds(2), null)));
    }

    //the listener and the read of the gateways write side by side: a reading heard earlier never replaces a later one
    @Test
    void should_never_replace_a_reading_by_one_its_gateway_heard_earlier() {
        save(67, false, HEARD.plusSeconds(2));
        save(67, true, HEARD);

        assertThat(loaded()).containsExactlyEntriesOf(Map.of(67L, new WindowSensors.Reading(false, HEARD.plusSeconds(2), null)));
    }

    @Test
    void should_keep_the_readings_of_the_listed_sensors_only() {
        save(67, true, HEARD);
        save(68, false, HEARD);
        save(56, false, HEARD);

        sut.retain(Set.of(67L, 56L, 999L)).as(StepVerifier::create).verifyComplete();

        assertThat(loaded()).containsOnlyKeys(67L, 56L);
    }

    private void save(final long sensor, final boolean open, final Instant updatedAt) {
        sut.save(sensor, new WindowSensors.Reading(open, updatedAt, LocalDateTime.now()))
            .as(StepVerifier::create)
            .verifyComplete();
    }

    private Map<Long, WindowSensors.Reading> loaded() {
        return sut.load().collectList().block().stream()
            .collect(Collectors.toMap(WindowSensorStore.Stored::sensorId, WindowSensorStore.Stored::reading));
    }
}
