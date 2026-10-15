package cloud.cholewa.blinds.driver;

import cloud.cholewa.blinds.error.BlindsError;
import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.BlindCommand;
import cloud.cholewa.home.model.BlindsPlacement;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class EatonDriverTest {

    private static final Blind KITCHEN = new Blind(17, "Kitchen window", RoomName.KITCHEN, SmartVendor.EATON,
        new BlindBinding.Eaton(9), BlindsPlacement.EXTERNAL, false);

    private MockWebServer server;
    private EatonDriver driver;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        driver = new EatonDriver(WebClient.builder().baseUrl("http://localhost:" + server.getPort()).build());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @ParameterizedTest
    @CsvSource({"OPEN,open", "CLOSE,close", "STOP,stop"})
    void should_send_the_command_for_the_point_to_amx_service(final BlindCommand command, final String parameter)
        throws InterruptedException {
        server.enqueue(new MockResponse.Builder().code(202)
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body("{\"sentAt\":\"2026-10-15T20:06:44\"}").build());

        StepVerifier.create(driver.move(KITCHEN, command)).verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/amx/eaton/blinds/9");
        assertThat(request.getUrl().queryParameter("command")).isEqualTo(parameter);
    }

    //the frame may have gone out or not - either way the command is failed, and the codes go to the log
    @Test
    void should_fail_with_the_status_and_the_codes_of_amx_service() {
        server.enqueue(new MockResponse.Builder().code(502)
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body("{\"errors\":[{\"message\":\"AMX frame server unavailable\",\"code\":\"FRAME_SERVER_UNAVAILABLE\"}]}")
            .build());

        StepVerifier.create(driver.move(KITCHEN, BlindCommand.CLOSE))
            .expectErrorSatisfies(e -> assertThat(e)
                .isInstanceOf(EatonDriver.AmxCallException.class)
                .hasMessageContaining("amx-service answered 502")
                .hasMessageContaining("FRAME_SERVER_UNAVAILABLE"))
            .verify();
    }

    @Test
    void should_not_send_an_eaton_actuator_to_a_position() {
        StepVerifier.create(driver.moveTo(KITCHEN, 50))
            .expectErrorSatisfies(e -> assertThat(((BlindsException) e).getError()).isEqualTo(BlindsError.UNSUPPORTED_POSITION))
            .verify();

        assertThat(server.getRequestCount()).isZero();
    }
}
