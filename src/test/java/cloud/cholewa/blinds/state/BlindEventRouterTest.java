package cloud.cholewa.blinds.state;

import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.BlindReading;
import cloud.cholewa.blinds.model.BlindState;
import cloud.cholewa.blinds.model.Direction;
import cloud.cholewa.blinds.registry.BlindRegistry;
import cloud.cholewa.home.model.BlindsPlacement;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.shelly.codec.ShellyDecodeException;
import cloud.cholewa.shelly.codec.ShellyMqttDecoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//the routing key decides first, by the registry; the body of an event of another device is not read
class BlindEventRouterTest {

    private static final Blind KITCHEN = new Blind(17, "Kitchen window", RoomName.KITCHEN, SmartVendor.EATON,
        new BlindBinding.Eaton(9), BlindsPlacement.EXTERNAL, false);
    private static final Blind DOOR = new Blind(53, "Office door", RoomName.OFFICE, SmartVendor.SHELLY,
        new BlindBinding.ShellyGen2("10.0.0.50", 0, "shellyplus2pm-08a6f77e5e34"), BlindsPlacement.EXTERNAL, true);
    private static final Blind WINDOW = new Blind(52, "Office window", RoomName.OFFICE, SmartVendor.SHELLY,
        new BlindBinding.ShellyGen1("10.0.0.59", 0), BlindsPlacement.EXTERNAL, true);

    private final BlindRegistry registry = mock(BlindRegistry.class);
    private final BlindStateTracker tracker = mock(BlindStateTracker.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final BlindEventRouter router =
        new BlindEventRouter(registry, tracker, new ShellyMqttDecoder(jsonMapper), jsonMapper);

    @BeforeEach
    void setUp() {
        when(registry.current()).thenReturn(List.of(KITCHEN, DOOR, WINDOW));
        when(tracker.observe(any(), any())).thenReturn(Mono.empty());
        when(tracker.reachability(any(), anyBoolean(), any())).thenReturn(Mono.empty());
    }

    @Test
    void should_take_the_status_of_an_eaton_actuator_as_its_movement() {
        route("eaton.blinds.9", """
            {"at":"2026-10-15T18:30:05","vendor":"eaton","gateway":"blinds","point":9,"event":"status","value":2}""");

        verify(tracker).observe(KITCHEN, new BlindReading(BlindState.CLOSING, null, Direction.CLOSE, null));
    }

    //a wall button of the blind is not its movement - the actuator reports that
    @Test
    void should_skip_an_eaton_event_that_is_not_a_status() {
        route("eaton.blinds.9", """
            {"at":"2026-10-15T18:30:05","vendor":"eaton","gateway":"blinds","point":9,"event":"up pressed"}""");

        verifyNoInteractions(tracker);
    }

    @Test
    void should_skip_a_status_that_is_none_of_a_blind() {
        route("eaton.blinds.9", """
            {"at":"2026-10-15T18:30:05","vendor":"eaton","gateway":"blinds","point":9,"event":"status","value":7}""");

        verifyNoInteractions(tracker);
    }

    //a sensor of the same gateway, a light, the group point: not a registered blind, the body is not read
    @ParameterizedTest
    @ValueSource(strings = {"eaton.blinds.45", "eaton.blinds.26", "eaton.lights.9", "eaton.blinds.x", "eaton.blinds."})
    void should_skip_an_eaton_point_that_is_not_a_blind(final String routingKey) {
        route(routingKey, "not json");

        verifyNoInteractions(tracker);
    }

    @Test
    void should_fail_on_an_eaton_body_that_is_not_an_event() {
        StepVerifier.create(router.route("eaton.blinds.9", "not json".getBytes(StandardCharsets.UTF_8)))
            .expectError()
            .verify();
    }

    @Test
    void should_take_the_status_of_a_cover() {
        route("shellyplus2pm-08a6f77e5e34.status.cover:0", """
            {"id":0,"source":"SHC","state":"opening","current_pos":12,"pos_control":true,"last_direction":"open"}""");

        verify(tracker).observe(DOOR, new BlindReading(BlindState.OPENING, 12, Direction.OPEN, "SHC"));
    }

    //an id copied from the page of the device into the registry in upper case
    @Test
    void should_match_the_id_of_a_cover_whatever_its_case() {
        route("SHELLYPLUS2PM-08A6F77E5E34.status.cover:0", """
            {"id":0,"state":"closed","current_pos":0,"pos_control":true}""");

        verify(tracker).observe(DOOR, new BlindReading(BlindState.CLOSED, 0, null, null));
    }

    @Test
    void should_skip_another_channel_and_another_component_of_the_cover() {
        route("shellyplus2pm-08a6f77e5e34.status.cover:1", """
            {"id":1,"source":"SHC","state":"opening"}""");
        route("shellyplus2pm-08a6f77e5e34.status.sys", "{}");
        route("shellyplus2pm-08a6f77e5e34.status.switch:0", "{}");

        verifyNoInteractions(tracker);
    }

    @Test
    void should_skip_a_state_the_service_does_not_read() {
        route("shellyplus2pm-08a6f77e5e34.status.cover:0", """
            {"id":0,"source":"init","state":"calibrating"}""");

        verifyNoInteractions(tracker);
    }

    @Test
    void should_take_the_last_will_of_a_cover_as_unreachable() {
        route("shellyplus2pm-08a6f77e5e34.online", "false");

        verify(tracker).reachability(eq(DOOR), eq(false), any());
    }

    @Test
    void should_take_a_connect_of_a_cover_as_reachable() {
        route("shellyplus2pm-08a6f77e5e34.online", "true");

        verify(tracker).reachability(eq(DOOR), eq(true), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"shellyplus1pm-a1b2c3.status.switch:0", "shellyplus1pm-a1b2c3.online", "PRINTER.status.switch:0", ""})
    void should_skip_a_device_that_is_not_a_blind(final String routingKey) {
        route(routingKey, "not json");

        verifyNoInteractions(tracker);
    }

    @Test
    void should_fail_on_a_cover_body_that_is_not_its_status() {
        StepVerifier.create(router.route("shellyplus2pm-08a6f77e5e34.status.cover:0",
                "{\"state\":\"open\"}".getBytes(StandardCharsets.UTF_8)))
            .expectError(ShellyDecodeException.class)
            .verify();
    }

    //the list as last read; until the first read nothing is a blind
    @Test
    void should_skip_everything_before_the_registry_was_read() {
        when(registry.current()).thenReturn(List.of());

        route("eaton.blinds.9", "{}");
        route("shellyplus2pm-08a6f77e5e34.status.cover:0", "{}");

        verifyNoInteractions(tracker);
    }

    private void route(final String routingKey, final String body) {
        StepVerifier.create(router.route(routingKey, body.getBytes(StandardCharsets.UTF_8))).verifyComplete();
    }
}
