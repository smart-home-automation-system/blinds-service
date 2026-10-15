package cloud.cholewa.blinds.service.schedule;

import cloud.cholewa.blinds.database.model.ScheduleConditionEntity;
import cloud.cholewa.blinds.database.model.ScheduleTargetEntity;
import cloud.cholewa.blinds.database.repository.ScheduleConditionRepository;
import cloud.cholewa.blinds.database.repository.ScheduleRepository;
import cloud.cholewa.blinds.database.repository.ScheduleTargetRepository;
import cloud.cholewa.blinds.error.BlindsError;
import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.schedule.Schedule;
import cloud.cholewa.blinds.model.schedule.ScheduleTarget;
import cloud.cholewa.home.model.RoomName;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static cloud.cholewa.blinds.service.schedule.ScheduleFixtures.sunsetClose;
import static cloud.cholewa.blinds.service.schedule.ScheduleFixtures.summerShading;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

//The schedules on the tables of V2, in a PostgreSQL of Testcontainers like BlindStateRepositoryTest:
//what is stored is what is read back, in the order given, and a write that fails leaves the
//schedule as it was - the three tables are written in one transaction. Without a Docker it fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import({ScheduleService.class, ScheduleServiceTest.MovableClock.class})
class ScheduleServiceTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final Instant MORNING = LocalDateTime.of(2026, 10, 15, 7, 30, 0, 123_456_789).atZone(HOUSE).toInstant();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final AtomicReference<Instant> NOW = new AtomicReference<>(MORNING);

    @Autowired
    private ScheduleService sut;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private ScheduleTargetRepository targetRepository;

    @MockitoSpyBean
    private ScheduleConditionRepository conditionRepository;

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
    void forgetTheSchedules() {
        reset(conditionRepository);
        NOW.set(MORNING);
        scheduleRepository.deleteAll().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_store_a_schedule_and_answer_it_as_stored() {
        final Schedule created = sut.create(summerShading().build()).block();

        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        //the time the column keeps, to the microsecond
        assertThat(created.createdAt()).isEqualTo(LocalDateTime.of(2026, 10, 15, 7, 30, 0, 123_456_000));
        assertThat(created.updatedAt()).isEqualTo(created.createdAt());
        assertThat(created).usingRecursiveComparison().ignoringFields("id", "createdAt", "updatedAt")
            .isEqualTo(summerShading().build());

        sut.get(created.id()).as(StepVerifier::create).expectNext(created).verifyComplete();
    }

    //the threshold column keeps one decimal: 200 is answered as 200, not as 200.0 or 2E+2
    @Test
    void should_answer_a_threshold_as_it_was_given() {
        final Schedule created = sut.create(sunsetClose().build()).block();

        assertThat(created).isNotNull();
        assertThat(created.conditions().getFirst().threshold()).isEqualTo(BigDecimal.valueOf(200)).hasScaleOf(0);
        assertThat(created.window().from().earliest()).hasToString("17:00");
    }

    @Test
    void should_keep_the_order_of_the_targets_and_the_conditions() {
        final List<ScheduleTarget> targets = List.of(ScheduleTarget.blind(53), ScheduleTarget.room(RoomName.KITCHEN),
            ScheduleTarget.room(RoomName.BEDROOM), ScheduleTarget.blind(17));

        final Schedule created = sut.create(sunsetClose().targets(targets).build()).block();
        final Schedule shading = sut.create(summerShading().build()).block();

        assertThat(created).isNotNull();
        assertThat(shading).isNotNull();
        sut.all().as(StepVerifier::create)
            .assertNext(all -> {
                assertThat(all).extracting(Schedule::id).containsExactly(created.id(), shading.id());
                assertThat(all.get(0).targets()).containsExactlyElementsOf(targets);
                assertThat(all.get(1).conditions()).containsExactlyElementsOf(summerShading().build().conditions());
            })
            .verifyComplete();
    }

    @Test
    void should_store_a_schedule_without_conditions() {
        final Schedule created = sut.create(sunsetClose().conditions(List.of()).build()).block();

        assertThat(created).isNotNull();
        assertThat(created.conditions()).isEmpty();
    }

    @Test
    void should_store_nothing_of_a_refused_schedule() {
        sut.create(sunsetClose().targets(List.of()).build()).as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(BlindsException.class,
                refusal -> assertThat(refusal.getError()).isEqualTo(BlindsError.INVALID_SCHEDULE)))
            .verify();

        scheduleRepository.count().as(StepVerifier::create).expectNext(0L).verifyComplete();
    }

    //whole: the targets and the conditions are the new ones; the id and createdAt survive it
    @Test
    void should_replace_a_schedule_whole() {
        final Schedule created = sut.create(sunsetClose().build()).block();
        assertThat(created).isNotNull();
        NOW.set(MORNING.plusSeconds(3600));

        final Schedule replaced = sut.replace(created.id(), summerShading().enabled(false).build()).block();

        assertThat(replaced).isNotNull();
        assertThat(replaced.id()).isEqualTo(created.id());
        assertThat(replaced.createdAt()).isEqualTo(created.createdAt());
        assertThat(replaced.updatedAt()).isEqualTo(created.createdAt().plusHours(1));
        assertThat(replaced).usingRecursiveComparison().ignoringFields("id", "createdAt", "updatedAt")
            .isEqualTo(summerShading().enabled(false).build());
        targetRepository.count().as(StepVerifier::create).expectNext(1L).verifyComplete();
        conditionRepository.count().as(StepVerifier::create).expectNext(3L).verifyComplete();
    }

    //the row of the schedule and its targets are written before the conditions: a condition that
    //fails takes them back with it
    @Test
    void should_leave_a_schedule_as_it_was_when_a_replace_fails() {
        final Schedule created = sut.create(sunsetClose().build()).block();
        assertThat(created).isNotNull();
        doAnswer(invocation -> Mono.error(new DataIntegrityViolationException("the disk is full")))
            .when(conditionRepository).save(any(ScheduleConditionEntity.class));
        NOW.set(MORNING.plusSeconds(3600));

        sut.replace(created.id(), summerShading().build()).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();

        reset(conditionRepository);
        sut.get(created.id()).as(StepVerifier::create).expectNext(created).verifyComplete();
    }

    @Test
    void should_store_nothing_when_a_create_fails() {
        doAnswer(invocation -> Mono.error(new DataIntegrityViolationException("the disk is full")))
            .when(conditionRepository).save(any(ScheduleConditionEntity.class));

        sut.create(sunsetClose().build()).as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();

        scheduleRepository.count().as(StepVerifier::create).expectNext(0L).verifyComplete();
        targetRepository.count().as(StepVerifier::create).expectNext(0L).verifyComplete();
    }

    @Test
    void should_refuse_to_replace_with_a_broken_schedule_and_keep_it() {
        final Schedule created = sut.create(sunsetClose().build()).block();
        assertThat(created).isNotNull();

        sut.replace(created.id(), summerShading().position(null).build()).as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(((BlindsException) error).getError())
                .isEqualTo(BlindsError.INVALID_SCHEDULE))
            .verify();

        sut.get(created.id()).as(StepVerifier::create).expectNext(created).verifyComplete();
    }

    @Test
    void should_remove_a_schedule_with_its_targets_and_conditions() {
        final Schedule created = sut.create(summerShading().build()).block();
        final Schedule other = sut.create(sunsetClose().build()).block();
        assertThat(created).isNotNull();
        assertThat(other).isNotNull();

        sut.remove(created.id()).as(StepVerifier::create).verifyComplete();

        sut.all().as(StepVerifier::create).expectNext(List.of(other)).verifyComplete();
        targetRepository.findAllByOrderById().map(ScheduleTargetEntity::scheduleId).as(StepVerifier::create)
            .expectNext(other.id(), other.id()).verifyComplete();
        conditionRepository.count().as(StepVerifier::create).expectNext(1L).verifyComplete();
    }

    @Test
    void should_switch_a_schedule_off_and_on() {
        final Schedule created = sut.create(sunsetClose().build()).block();
        assertThat(created).isNotNull();
        NOW.set(MORNING.plusSeconds(60));

        sut.disable(created.id()).as(StepVerifier::create)
            .assertNext(disabled -> {
                assertThat(disabled.enabled()).isFalse();
                assertThat(disabled.updatedAt()).isEqualTo(created.updatedAt().plusMinutes(1));
                assertThat(disabled.targets()).isEqualTo(created.targets());
                assertThat(disabled.conditions()).isEqualTo(created.conditions());
            })
            .verifyComplete();

        NOW.set(MORNING.plusSeconds(120));
        sut.enable(created.id()).map(Schedule::enabled).as(StepVerifier::create).expectNext(true).verifyComplete();
    }

    //a switch to the state it is in writes nothing
    @Test
    void should_not_move_updated_at_for_a_switch_to_the_same_state() {
        final Schedule created = sut.create(sunsetClose().build()).block();
        assertThat(created).isNotNull();
        NOW.set(MORNING.plusSeconds(60));

        sut.enable(created.id()).as(StepVerifier::create).expectNext(created).verifyComplete();
    }

    @Test
    void should_answer_an_unknown_schedule_with_its_code() {
        final long unknown = 999_999;

        Mono.when(
                expectNotFound(sut.get(unknown)),
                expectNotFound(sut.replace(unknown, sunsetClose().build())),
                expectNotFound(sut.remove(unknown)),
                expectNotFound(sut.enable(unknown)),
                expectNotFound(sut.disable(unknown)))
            .as(StepVerifier::create)
            .verifyComplete();

        scheduleRepository.count().as(StepVerifier::create).expectNext(0L).verifyComplete();
    }

    @Test
    void should_answer_no_schedules_as_an_empty_list() {
        sut.all().as(StepVerifier::create).expectNext(List.of()).verifyComplete();
    }

    //the schema repeats what one row can say, for a write that would get past the service
    @Test
    void should_refuse_a_row_the_rules_would_refuse() {
        final Schedule created = sut.create(sunsetClose().build()).block();
        assertThat(created).isNotNull();

        databaseClient.sql("UPDATE blinds_schedule SET position = 50 WHERE id = :id").bind("id", created.id())
            .fetch().rowsUpdated()
            .as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
        databaseClient.sql("INSERT INTO blinds_schedule_target (schedule_id, room, device_id) VALUES (:id, 'OFFICE', 53)")
            .bind("id", created.id())
            .fetch().rowsUpdated()
            .as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
        databaseClient.sql("""
                INSERT INTO blinds_schedule_condition (schedule_id, metric, operator, threshold, expected, hold_minutes)
                VALUES (:id, 'RAINING', 'IS', 1, TRUE, 0)""")
            .bind("id", created.id())
            .fetch().rowsUpdated()
            .as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
        databaseClient.sql("UPDATE blinds_schedule SET window_to_offset_min = 10 WHERE id = :id").bind("id", created.id())
            .fetch().rowsUpdated()
            .as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
        //a CHECK that comes out NULL lets a row in: a missing offset and a missing position are refused
        //by name
        databaseClient.sql("UPDATE blinds_schedule SET window_from_offset_min = NULL WHERE id = :id")
            .bind("id", created.id())
            .fetch().rowsUpdated()
            .as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
        databaseClient.sql("UPDATE blinds_schedule SET action = 'POSITION' WHERE id = :id").bind("id", created.id())
            .fetch().rowsUpdated()
            .as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class)
            .verify();
    }

    private static Mono<Void> expectNotFound(final Mono<?> call) {
        return call.then(Mono.<Void>error(new AssertionError("the call did not fail")))
            .onErrorResume(BlindsException.class, refusal -> {
                assertThat(refusal.getError()).isEqualTo(BlindsError.NOT_FOUND_SCHEDULE);
                return Mono.empty();
            });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MovableClock {

        @Bean
        Clock clock() {
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return HOUSE;
                }

                @Override
                public Clock withZone(final ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return NOW.get();
                }
            };
        }
    }
}
