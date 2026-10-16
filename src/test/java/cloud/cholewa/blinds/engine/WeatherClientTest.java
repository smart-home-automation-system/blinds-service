package cloud.cholewa.blinds.engine;

import cloud.cholewa.blinds.engine.WeatherNow.Observation;
import cloud.cholewa.blinds.model.schedule.ConditionMetric;
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
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherClientTest {

    private static final LocalDateTime MEASURED = LocalDateTime.of(2026, 10, 16, 17, 2, 31);

    private MockWebServer server;
    private WeatherClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new WeatherClient(WebClient.builder().baseUrl("http://localhost:" + server.getPort()).build());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    //the answer of weather-service 0.4.0: only the four metrics a condition names are kept
    @Test
    void should_read_the_metrics_a_condition_can_name() throws InterruptedException {
        server.enqueue(json("""
            {"temperature":{"value":12.4,"measuredAt":"2026-10-16T17:02:31","stale":false},
             "illuminance":{"value":180,"measuredAt":"2026-10-16T17:02:31","stale":false},
             "gust":{"value":3.1,"measuredAt":"2026-10-16T17:02:31","stale":true},
             "raining":{"value":false,"measuredAt":"2026-10-16T17:02:31","stale":false},
             "humidity":{"value":81,"measuredAt":"2026-10-16T17:02:31","stale":false}}
            """));

        client.current().as(StepVerifier::create)
            .assertNext(metrics -> assertThat(metrics)
                .containsOnlyKeys(ConditionMetric.OUTSIDE_TEMPERATURE, ConditionMetric.ILLUMINANCE,
                    ConditionMetric.WIND_GUST, ConditionMetric.RAINING)
                .containsEntry(ConditionMetric.OUTSIDE_TEMPERATURE, new Observation(12.4, MEASURED, false))
                .containsEntry(ConditionMetric.ILLUMINANCE, new Observation(180, MEASURED, false))
                .containsEntry(ConditionMetric.WIND_GUST, new Observation(3.1, MEASURED, true))
                .containsEntry(ConditionMetric.RAINING, new Observation(false, MEASURED, false)))
            .verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/weather/current");
    }

    //a metric never received is not in the answer, and one without a value or a time is not kept
    @Test
    void should_leave_out_what_the_answer_does_not_say() {
        server.enqueue(json("""
            {"illuminance":{"value":null,"measuredAt":"2026-10-16T17:02:31","stale":false},
             "gust":{"value":3.1,"stale":false}}
            """));

        client.current().as(StepVerifier::create)
            .assertNext(metrics -> assertThat(metrics).isEmpty())
            .verifyComplete();
    }

    @Test
    void should_fail_on_no_body_and_on_a_failing_weather_service() {
        server.enqueue(new MockResponse.Builder().code(200).build());
        server.enqueue(new MockResponse.Builder().code(503)
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body("{\"errors\":[{\"message\":\"Service unavailable\"}]}").build());

        client.current().as(StepVerifier::create).expectError(InputCallException.class).verify();
        client.current().as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(e).hasMessage("weather-service answered 503"))
            .verify();
    }

    @Test
    void should_fail_on_a_body_that_is_not_a_map() {
        server.enqueue(json("[1,2]"));

        client.current().as(StepVerifier::create).expectError().verify();
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder()
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build();
    }
}
