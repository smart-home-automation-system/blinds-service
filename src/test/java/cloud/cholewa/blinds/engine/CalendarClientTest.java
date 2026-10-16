package cloud.cholewa.blinds.engine;

import cloud.cholewa.home.model.DayType;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarClientTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 11);

    private MockWebServer server;
    private CalendarClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new CalendarClient(WebClient.builder().baseUrl("http://localhost:" + server.getPort()).build());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void should_read_the_type_of_the_day() throws InterruptedException {
        server.enqueue(json("""
            [{"date":"2026-11-11","type":"day off","reason":"Independence Day"}]
            """));

        client.typeOf(DAY).as(StepVerifier::create).expectNext(DayType.DAY_OFF).verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/calendar/days");
        assertThat(request.getUrl().queryParameter("from")).isEqualTo("2026-11-11");
        assertThat(request.getUrl().queryParameter("to")).isEqualTo("2026-11-12");
    }

    //never a workday by default
    @Test
    void should_fail_when_the_day_is_not_in_the_answer() {
        server.enqueue(json("""
            [{"date":"2026-11-10","type":"workday"}]
            """));

        client.typeOf(DAY).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(InputCallException.class)
                .hasMessage("the calendar has no type for 2026-11-11"))
            .verify();
    }

    @Test
    void should_fail_on_an_empty_list_and_on_no_body() {
        server.enqueue(json("[]"));
        server.enqueue(new MockResponse.Builder().code(200).build());

        client.typeOf(DAY).as(StepVerifier::create).expectError(InputCallException.class).verify();
        client.typeOf(DAY).as(StepVerifier::create).expectError(InputCallException.class).verify();
    }

    @Test
    void should_fail_when_database_service_fails() {
        server.enqueue(new MockResponse.Builder().code(500)
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body("{\"errors\":[{\"message\":\"Internal error\"}]}").build());

        client.typeOf(DAY).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(InputCallException.class)
                .hasMessage("database-service answered 500"))
            .verify();
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder()
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build();
    }
}
