package cloud.cholewa.blinds.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.blinds.config.ExceptionHandlerConfig;
import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.error.processor.BlindsExceptionProcessor;
import cloud.cholewa.blinds.model.schedule.Schedule;
import cloud.cholewa.blinds.service.BlindsCommandService;
import cloud.cholewa.blinds.service.BlindsQueryService;
import cloud.cholewa.blinds.service.schedule.ScheduleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

import static cloud.cholewa.blinds.service.schedule.ScheduleFixtures.sunsetClose;
import static cloud.cholewa.blinds.service.schedule.ScheduleFixtures.summerShading;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//The wire shape of a schedule - the values of the API, what is left out - and the statuses and
//codes. The three controllers together, because "/schedules" must never be taken for the {id} of a
//blind
@Import(ExceptionHandlerConfig.class)
@WebFluxTest({BlindsScheduleController.class, BlindsReadController.class, BlindsController.class})
class BlindsScheduleControllerTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 15, 7, 30, 0, 123_456_000);

    private static final String SUNSET_CLOSE = """
        {
          "name": "Evening close",
          "enabled": true,
          "dayType": "any",
          "window": {
            "from": {"kind": "sunset", "offsetMinutes": -30, "earliest": "17:00", "latest": "21:30"},
            "to": {"kind": "clock", "time": "23:00"}
          },
          "targets": [{"room": "living room"}, {"deviceId": 53}],
          "conditions": [{"metric": "illuminance", "operator": "lt", "threshold": 200, "holdMinutes": 10}],
          "action": "close"
        }""";

    private final ListAppender<ILoggingEvent> processorLog = new ListAppender<>();

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ScheduleService scheduleService;

    @MockitoBean
    private BlindsQueryService blindsQueryService;

    @MockitoBean
    private BlindsCommandService blindsCommandService;

    @BeforeEach
    void setUp() {
        processorLog.start();
        ((Logger) LoggerFactory.getLogger(BlindsExceptionProcessor.class)).addAppender(processorLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(BlindsExceptionProcessor.class)).detachAppender(processorLog);
    }

    //the body of the README reads as the schedule of the fixtures
    @Test
    void should_create_a_schedule_from_the_values_of_the_api() {
        final ArgumentCaptor<Schedule> body = ArgumentCaptor.forClass(Schedule.class);
        when(scheduleService.create(body.capture())).thenReturn(Mono.just(stored(sunsetClose().build(), 1)));

        webTestClient.post().uri("/schedules")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(SUNSET_CLOSE)
            .exchange()
            .expectStatus().isCreated()
            .expectBody()
            .jsonPath("$.id").isEqualTo(1)
            .jsonPath("$.createdAt").isEqualTo("2026-10-15T07:30:00.123456");

        assertThat(body.getValue()).isEqualTo(sunsetClose().build());
    }

    @Test
    void should_answer_a_schedule_in_the_values_of_the_api_without_what_it_does_not_have() {
        when(scheduleService.get(1)).thenReturn(Mono.just(stored(sunsetClose().build(), 1)));

        webTestClient.get().uri("/schedules/1")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.name").isEqualTo("Evening close")
            .jsonPath("$.enabled").isEqualTo(true)
            .jsonPath("$.dayType").isEqualTo("any")
            .jsonPath("$.window.from.kind").isEqualTo("sunset")
            .jsonPath("$.window.from.offsetMinutes").isEqualTo(-30)
            .jsonPath("$.window.from.earliest").isEqualTo("17:00")
            .jsonPath("$.window.from.latest").isEqualTo("21:30")
            .jsonPath("$.window.from.time").doesNotExist()
            .jsonPath("$.window.to.kind").isEqualTo("clock")
            .jsonPath("$.window.to.time").isEqualTo("23:00")
            .jsonPath("$.window.to.offsetMinutes").doesNotExist()
            .jsonPath("$.targets[0].room").isEqualTo("living room")
            .jsonPath("$.targets[0].deviceId").doesNotExist()
            .jsonPath("$.targets[1].deviceId").isEqualTo(53)
            .jsonPath("$.targets[1].room").doesNotExist()
            .jsonPath("$.conditions[0].metric").isEqualTo("illuminance")
            .jsonPath("$.conditions[0].operator").isEqualTo("lt")
            .jsonPath("$.conditions[0].threshold").isEqualTo(200)
            .jsonPath("$.conditions[0].value").doesNotExist()
            .jsonPath("$.conditions[0].holdMinutes").isEqualTo(10)
            .jsonPath("$.action").isEqualTo("close")
            .jsonPath("$.position").doesNotExist()
            .jsonPath("$.updatedAt").isEqualTo("2026-10-15T07:30:00.123456");
    }

    @Test
    void should_answer_every_schedule() {
        when(scheduleService.all()).thenReturn(Mono.just(List.of(
            stored(sunsetClose().build(), 1), stored(summerShading().build(), 2))));

        webTestClient.get().uri("/schedules")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$[0].id").isEqualTo(1)
            .jsonPath("$[1].dayType").isEqualTo("workday")
            .jsonPath("$[1].conditions[0].metric").isEqualTo("outside temperature")
            .jsonPath("$[1].conditions[0].threshold").isEqualTo(25.5)
            .jsonPath("$[1].conditions[2].metric").isEqualTo("window closed")
            .jsonPath("$[1].conditions[2].operator").isEqualTo("is")
            .jsonPath("$[1].conditions[2].value").isEqualTo(true)
            .jsonPath("$[1].conditions[2].threshold").doesNotExist()
            .jsonPath("$[1].action").isEqualTo("position")
            .jsonPath("$[1].position").isEqualTo(30);

        verifyNoInteractions(blindsQueryService);
    }

    //the id and the times are the service's: a body that sends them does not set them
    @Test
    void should_replace_a_schedule_ignoring_what_is_the_services() {
        final ArgumentCaptor<Schedule> body = ArgumentCaptor.forClass(Schedule.class);
        when(scheduleService.replace(eq(1L), body.capture())).thenReturn(Mono.just(stored(sunsetClose().build(), 1)));

        webTestClient.put().uri("/schedules/1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(SUNSET_CLOSE.replace("\"name\"", "\"id\": 7, \"createdAt\": \"2020-01-01T00:00:00\", \"name\""))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.id").isEqualTo(1);

        assertThat(body.getValue()).isEqualTo(sunsetClose().build());
    }

    @Test
    void should_switch_a_schedule() {
        when(scheduleService.disable(1)).thenReturn(Mono.just(stored(sunsetClose().enabled(false).build(), 1)));
        when(scheduleService.enable(1)).thenReturn(Mono.just(stored(sunsetClose().build(), 1)));

        webTestClient.post().uri("/schedules/1/disable")
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.enabled").isEqualTo(false);
        webTestClient.post().uri("/schedules/1/enable")
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.enabled").isEqualTo(true);
    }

    @Test
    void should_remove_a_schedule() {
        when(scheduleService.remove(1)).thenReturn(Mono.empty());

        webTestClient.delete().uri("/schedules/1")
            .exchange()
            .expectStatus().isNoContent()
            .expectBody().isEmpty();

        verify(scheduleService).remove(1);
    }

    //a rule broken is the caller's: a WARN, and the reason in the answer
    @Test
    void should_answer_a_refused_schedule_with_its_code_and_reason() {
        when(scheduleService.create(any()))
            .thenReturn(Mono.error(BlindsException.invalidSchedule("targets has at least one room or blind")));

        webTestClient.post().uri("/schedules")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(SUNSET_CLOSE)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("INVALID_SCHEDULE")
            .jsonPath("$.errors[0].message").isEqualTo("Invalid schedule")
            .jsonPath("$.errors[0].details").isEqualTo("targets has at least one room or blind");

        assertThat(processorLog.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
    }

    @Test
    void should_answer_an_unknown_schedule_with_its_code() {
        when(scheduleService.get(anyLong())).thenReturn(Mono.error(BlindsException.scheduleNotFound(9)));

        webTestClient.get().uri("/schedules/9")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_SCHEDULE")
            .jsonPath("$.errors[0].details").isEqualTo("no schedule with id 9");
    }

    //a value the API does not have - also in another case - is a body that cannot be read
    @ParameterizedTest
    @ValueSource(strings = {
        "\"dayType\": \"any\"|\"dayType\": \"weekend\"",
        "\"dayType\": \"any\"|\"dayType\": \"ANY\"",
        "\"kind\": \"sunset\"|\"kind\": \"dusk\"",
        "\"metric\": \"illuminance\"|\"metric\": \"lux\"",
        "\"operator\": \"lt\"|\"operator\": \"<\"",
        "\"action\": \"close\"|\"action\": \"stop\"",
        "\"room\": \"living room\"|\"room\": \"attic\"",
        "\"time\": \"23:00\"|\"time\": \"25:00\"",
        "\"time\": \"23:00\"|\"time\": \"23:00:00\"",
        "\"deviceId\": 53|\"deviceId\": \"door\""
    })
    void should_refuse_a_body_with_a_value_the_api_does_not_have(final String replacement) {
        final String[] parts = replacement.split("\\|");

        webTestClient.post().uri("/schedules")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(SUNSET_CLOSE.replace(parts[0], parts[1]))
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(scheduleService);
    }

    @Test
    void should_refuse_a_schedule_id_that_is_not_a_number() {
        webTestClient.get().uri("/schedules/abc")
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(scheduleService, blindsQueryService);
    }

    private static Schedule stored(final Schedule schedule, final long id) {
        return new Schedule(id, schedule.name(), schedule.enabled(), schedule.dayType(), schedule.window(),
            schedule.targets(), schedule.conditions(), schedule.action(), schedule.position(), CREATED, CREATED);
    }
}
