package cloud.cholewa.blinds.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.blinds.config.ExceptionHandlerConfig;
import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.error.processor.BlindsExceptionProcessor;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.BlindCommand;
import cloud.cholewa.blinds.model.CommandSource;
import cloud.cholewa.blinds.model.RoomCommandReply;
import cloud.cholewa.blinds.service.BlindsCommandService;
import cloud.cholewa.home.model.BlindsPlacement;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//what a caller gets: the status, the body and - for a failure - the code and the level of the log
//line, which is what the alerts look at
@Import(ExceptionHandlerConfig.class)
@WebFluxTest(BlindsController.class)
class BlindsControllerTest {

    private static final Blind KITCHEN = new Blind(17, "Kitchen window", RoomName.KITCHEN, SmartVendor.EATON,
        new BlindBinding.Eaton(9), BlindsPlacement.EXTERNAL, false);

    private final ListAppender<ILoggingEvent> processorLog = new ListAppender<>();

    @Autowired
    private WebTestClient webTestClient;

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

    @ParameterizedTest
    @CsvSource({"open,OPEN", "close,CLOSE", "stop,STOP"})
    void should_accept_a_command_for_a_blind(final String parameter, final BlindCommand command) {
        when(blindsCommandService.move(17, command, CommandSource.DASHBOARD)).thenReturn(Mono.empty());

        webTestClient.post().uri("/17?command={command}", parameter)
            .exchange()
            .expectStatus().isAccepted()
            .expectBody().isEmpty();

        verify(blindsCommandService).move(17, command, CommandSource.DASHBOARD);
    }

    @ParameterizedTest
    @CsvSource({"dashboard,DASHBOARD", "schedule,SCHEDULE", "protection,PROTECTION"})
    void should_pass_who_asked_for_the_move(final String parameter, final CommandSource source) {
        when(blindsCommandService.move(17, BlindCommand.OPEN, source)).thenReturn(Mono.empty());

        webTestClient.post().uri("/17?command=open&source={source}", parameter)
            .exchange()
            .expectStatus().isAccepted();

        verify(blindsCommandService).move(17, BlindCommand.OPEN, source);
    }

    @Test
    void should_accept_a_position() {
        when(blindsCommandService.moveTo(21, 40, CommandSource.DASHBOARD)).thenReturn(Mono.empty());

        webTestClient.post().uri("/21/position?value=40")
            .exchange()
            .expectStatus().isAccepted();
    }

    //a typo moves nothing
    @ParameterizedTest
    @ValueSource(strings = {
        "/17?command=OPEN", "/17?command=up", "/17", "/17?command=open&source=bruno", "/abc?command=open",
        "/21/position?value=101", "/21/position?value=-1", "/21/position", "/rooms/attic?command=open",
        "/rooms/office?command=down"
    })
    void should_refuse_what_is_not_a_command(final String uri) {
        webTestClient.post().uri(uri)
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(blindsCommandService);
    }

    @Test
    void should_answer_an_unknown_blind_with_its_code() {
        when(blindsCommandService.move(anyLong(), any(), any()))
            .thenReturn(Mono.error(BlindsException.notFound("no blind with id 99")));

        webTestClient.post().uri("/99?command=open")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_BLINDS")
            .jsonPath("$.errors[0].details").isEqualTo("no blind with id 99");

        assertThat(processorLog.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
    }

    @Test
    void should_answer_a_position_for_a_blind_without_positioning_with_its_code() {
        when(blindsCommandService.moveTo(anyLong(), anyInt(), any()))
            .thenReturn(Mono.error(BlindsException.unsupportedPosition(KITCHEN)));

        webTestClient.post().uri("/17/position?value=50")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("UNSUPPORTED_POSITION");
    }

    @Test
    void should_answer_a_refusal_of_the_protection_with_409() {
        when(blindsCommandService.move(anyLong(), any(), any()))
            .thenReturn(Mono.error(BlindsException.protectionActive(KITCHEN, "wind above the limit")));

        webTestClient.post().uri("/17?command=open")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("PROTECTION_ACTIVE");
    }

    //the status and the code alone: what the device said is in the log, at ERROR
    @Test
    void should_answer_a_failed_device_with_502_without_what_the_device_said() {
        when(blindsCommandService.move(anyLong(), any(), any())).thenReturn(Mono.error(
            BlindsException.deviceFailed(KITCHEN, new IllegalStateException("amx-service answered 502 secret-ish"))));

        webTestClient.post().uri("/17?command=open")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody()
            .jsonPath("$.errors[0].code").isEqualTo("DEVICE_FAILED")
            .jsonPath("$.errors[0].details").doesNotExist()
            .consumeWith(result -> assertThat(new String(result.getResponseBodyContent())).doesNotContain("secret-ish"));

        assertThat(processorLog.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("secret-ish");
        });
    }

    @Test
    void should_answer_a_room_with_what_moved_and_what_did_not() {
        when(blindsCommandService.moveRoom(RoomName.LIVING_ROOM, BlindCommand.CLOSE, CommandSource.DASHBOARD))
            .thenReturn(Mono.just(new RoomCommandReply("living room", "close",
                List.of(new RoomCommandReply.BlindOutcome(18, "Living room window", null)),
                List.of(new RoomCommandReply.BlindOutcome(23, "Living room door", "DEVICE_FAILED")))));

        webTestClient.post().uri("/rooms/living room?command=close")
            .exchange()
            .expectStatus().isAccepted()
            .expectBody()
            .jsonPath("$.room").isEqualTo("living room")
            .jsonPath("$.moved[0].id").isEqualTo(18)
            .jsonPath("$.moved[0].code").doesNotExist()
            .jsonPath("$.failed[0].id").isEqualTo(23)
            .jsonPath("$.failed[0].code").isEqualTo("DEVICE_FAILED");
    }
}
