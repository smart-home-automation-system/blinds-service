package cloud.cholewa.blinds.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.blinds.config.ExceptionHandlerConfig;
import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.error.processor.BlindsExceptionProcessor;
import cloud.cholewa.blinds.model.BlindView;
import cloud.cholewa.blinds.model.RoomBlinds;
import cloud.cholewa.blinds.service.BlindsQueryService;
import cloud.cholewa.blinds.state.Gen1Action;
import cloud.cholewa.home.model.RoomName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Import(ExceptionHandlerConfig.class)
@WebFluxTest(BlindsReadController.class)
class BlindsReadControllerTest {

    private static final BlindView DOOR = new BlindView(53, "Office door", "office", "shelly", "external", true,
        "stopped", 40, "close", LocalDateTime.of(2026, 10, 15, 18, 0, 5), "SHC", true, "closed",
        LocalDateTime.of(2026, 10, 15, 21, 30));
    //nothing heard of it yet
    private static final BlindView KITCHEN = new BlindView(17, "Kitchen window", "kitchen", "eaton", "external", false,
        null, null, null, null, null, null, null, null);

    private final ListAppender<ILoggingEvent> processorLog = new ListAppender<>();

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private BlindsQueryService blindsQueryService;

    @BeforeEach
    void setUp() {
        processorLog.start();
        ((Logger) LoggerFactory.getLogger(BlindsExceptionProcessor.class)).addAppender(processorLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(BlindsExceptionProcessor.class)).detachAppender(processorLog);
    }

    @Test
    void should_answer_every_blind_by_room() {
        when(blindsQueryService.all()).thenReturn(Mono.just(List.of(
            new RoomBlinds("kitchen", List.of(KITCHEN)), new RoomBlinds("office", List.of(DOOR)))));

        webTestClient.get().uri("")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$[0].room").isEqualTo("kitchen")
            .jsonPath("$[1].blinds[0].id").isEqualTo(53)
            .jsonPath("$[1].blinds[0].state").isEqualTo("stopped")
            .jsonPath("$[1].blinds[0].position").isEqualTo(40)
            .jsonPath("$[1].blinds[0].lastDirection").isEqualTo("close")
            .jsonPath("$[1].blinds[0].movedAt").isEqualTo("2026-10-15T18:00:05")
            .jsonPath("$[1].blinds[0].source").isEqualTo("SHC")
            .jsonPath("$[1].blinds[0].reachable").isEqualTo(true)
            .jsonPath("$[1].blinds[0].schedulePausedSince").isEqualTo("2026-10-15T21:30:00")
            .jsonPath("$[0].blinds[0].schedulePausedSince").doesNotExist();
    }

    //what is not known is left out - never answered as closed, zero or unreachable
    @Test
    void should_leave_out_what_is_not_known() {
        when(blindsQueryService.one(17)).thenReturn(Mono.just(KITCHEN));

        webTestClient.get().uri("/17")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.name").isEqualTo("Kitchen window")
            .jsonPath("$.positioning").isEqualTo(false)
            .jsonPath("$.state").doesNotExist()
            .jsonPath("$.position").doesNotExist()
            .jsonPath("$.lastDirection").doesNotExist()
            .jsonPath("$.movedAt").doesNotExist()
            .jsonPath("$.source").doesNotExist()
            .jsonPath("$.reachable").doesNotExist();
    }

    @Test
    void should_answer_the_blinds_of_a_room() {
        when(blindsQueryService.inRoom(RoomName.LIVING_ROOM))
            .thenReturn(Mono.just(new RoomBlinds("living room", List.of())));

        webTestClient.get().uri("/rooms/living room")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.room").isEqualTo("living room");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/rooms/attic", "/rooms/Office", "/abc"})
    void should_refuse_what_is_not_a_blind_or_a_room(final String uri) {
        webTestClient.get().uri(uri)
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(blindsQueryService);
    }

    @Test
    void should_answer_an_unknown_blind_with_its_code() {
        when(blindsQueryService.one(anyLong())).thenReturn(Mono.error(BlindsException.notFound("no blind with id 99")));

        webTestClient.get().uri("/99")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_BLINDS");
    }

    @ParameterizedTest
    @CsvSource({"open,OPEN", "close,CLOSE", "stop,STOP"})
    void should_take_an_action_of_a_gen1_roller(final String event, final Gen1Action action) {
        when(blindsQueryService.gen1Action(52, action)).thenReturn(Mono.empty());

        webTestClient.get().uri("/events/gen1?device=52&event={event}", event)
            .exchange()
            .expectStatus().isNoContent()
            .expectBody().isEmpty();

        verify(blindsQueryService).gen1Action(52, action);
    }

    //a device configured with a wrong id: a WARN, which is what tells somebody to fix the device
    @Test
    void should_answer_an_action_of_an_unknown_device_with_404_logged_at_warn() {
        when(blindsQueryService.gen1Action(anyLong(), any()))
            .thenReturn(Mono.error(BlindsException.notFound("no Gen1 blind with id 99")));

        webTestClient.get().uri("/events/gen1?device=99&event=open")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_BLINDS");

        assertThat(processorLog.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/events/gen1?device=52&event=OPEN", "/events/gen1?device=52&event=up", "/events/gen1?device=52",
        "/events/gen1?event=open", "/events/gen1?device=abc&event=open"
    })
    void should_refuse_what_is_not_an_action(final String uri) {
        webTestClient.get().uri(uri)
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(blindsQueryService);
    }
}
