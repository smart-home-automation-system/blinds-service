package cloud.cholewa.blinds.config;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.zalando.logbook.Logbook;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WebClientConfigTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void should_call_the_address_of_the_service() throws InterruptedException {
        server.enqueue(new MockResponse.Builder().code(200).body("ok").build());

        StepVerifier.create(client(Duration.ofSeconds(5)).get().uri("/home/device/registry")
                .retrieve().bodyToMono(String.class))
            .expectNext("ok")
            .verifyComplete();

        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/home/device/registry");
    }

    //without the response timeout a service that accepts the connection and never answers would
    //hold the call forever
    @Test
    void should_give_up_on_a_service_that_does_not_answer_in_time() {
        server.enqueue(new MockResponse.Builder().code(200).body("late")
            .headersDelay(3, TimeUnit.SECONDS).build());

        StepVerifier.create(client(Duration.ofSeconds(1)).get().uri("/")
                .retrieve().bodyToMono(String.class))
            .expectError(WebClientRequestException.class)
            .verify(Duration.ofSeconds(10));
    }

    private WebClient client(final Duration responseTimeout) {
        return WebClientConfig.webClient(
            WebClient.builder(),
            Logbook.create(),
            new InternalServiceProperties.Endpoint(
                "http://localhost:" + server.getPort(), Duration.ofSeconds(1), responseTimeout)
        );
    }
}
