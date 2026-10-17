package cloud.cholewa.blinds.registry;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartDevice;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceRegistryClientTest {

    private MockWebServer server;
    private DeviceRegistryClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new DeviceRegistryClient(WebClient.builder().baseUrl("http://localhost:" + server.getPort()).build());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void should_read_the_switched_on_blinds() throws InterruptedException {
        server.enqueue(json("""
            [{"id":17,"name":"Kitchen window","room":"kitchen","kind":"blinds","enabled":true,"vendor":"eaton",
              "eaton":{"gateway":"blinds","point":9},"placement":"external","positioning":false}]
            """));

        client.getBlinds().as(StepVerifier::create)
            .assertNext(devices -> assertThat(devices).extracting(SmartDevice::getId, SmartDevice::getRoom)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(17L, RoomName.KITCHEN)))
            .verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/device/registry");
        assertThat(request.getUrl().queryParameter("kind")).isEqualTo("blinds");
        assertThat(request.getUrl().queryParameter("enabled")).isEqualTo("true");
    }

    //HAS-290: every opening sensor, switched on or off, and the switched-on BTHome gateways
    @Test
    void should_read_the_opening_sensors_and_the_bthome_gateways() throws InterruptedException {
        server.enqueue(json("[]"));
        server.enqueue(json("[]"));

        client.getOpeningSensors().as(StepVerifier::create).expectNext(List.of()).verifyComplete();
        client.getBthomeGateways().as(StepVerifier::create).expectNext(List.of()).verifyComplete();

        final RecordedRequest sensors = server.takeRequest();
        assertThat(sensors.getUrl().queryParameter("kind")).isEqualTo("opening sensor");
        assertThat(sensors.getUrl().queryParameterNames()).doesNotContain("enabled");
        final RecordedRequest gateways = server.takeRequest();
        assertThat(gateways.getUrl().queryParameter("kind")).isEqualTo("bthome gateway");
        assertThat(gateways.getUrl().queryParameter("enabled")).isEqualTo("true");
    }

    @Test
    void should_read_an_empty_registry_as_no_blinds() {
        server.enqueue(json("[]"));

        client.getBlinds().as(StepVerifier::create)
            .assertNext(devices -> assertThat(devices).isEmpty())
            .verifyComplete();
    }

    //an error is never an empty list - that would make every blind unknown
    @Test
    void should_fail_when_database_service_fails() {
        server.enqueue(new MockResponse.Builder().code(500)
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body("{\"errors\":[{\"message\":\"Internal error\"}]}").build());

        client.getBlinds().as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(e)
                .isInstanceOf(RegistryCallException.class)
                .hasMessage("database-service answered 500"))
            .verify();
    }

    @Test
    void should_fail_on_an_answer_without_a_body() {
        server.enqueue(new MockResponse.Builder().code(200).build());

        client.getBlinds().as(StepVerifier::create)
            .expectError(RegistryCallException.class)
            .verify();
    }

    @Test
    void should_fail_on_an_answer_that_is_not_a_list() {
        server.enqueue(json("{}"));

        client.getBlinds().as(StepVerifier::create)
            .expectError()
            .verify();
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder().code(200)
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body).build();
    }
}
