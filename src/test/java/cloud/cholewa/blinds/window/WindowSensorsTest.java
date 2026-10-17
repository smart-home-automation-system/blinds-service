package cloud.cholewa.blinds.window;

import cloud.cholewa.blinds.config.WindowProperties;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.WindowState;
import cloud.cholewa.blinds.registry.DeviceRegistryClient;
import cloud.cholewa.blinds.registry.RegistryCallException;
import cloud.cholewa.home.model.BlindsPlacement;
import cloud.cholewa.home.model.BthomeBinding;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.ShellyBinding;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.model.BthomeSensorStatus;
import cloud.cholewa.shelly.model.ShellyComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//The window of one blind from the sensors the registry links to it (HAS-290): the components of the
//gateways, their status topics, and what a silent gateway does to its windows
class WindowSensorsTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 17, 20, 0);
    private static final String TREADMILL = "shellyplugsg3-28372f2f0460";
    private static final String OFFICE_MAIN = "shelly1pmg3-aabbccddeeff";
    private static final String LEFT = "60:ef:ab:44:2b:dd";
    private static final String RIGHT = "8c:6f:b9:13:f4:dd";
    private static final String DOOR = "38:39:8f:9c:ea:c3";

    private final AtomicReference<LocalDateTime> now = new AtomicReference<>(START);
    private final DeviceRegistryClient registry = mock(DeviceRegistryClient.class);
    private final ShellyRpcClient rpc = mock(ShellyRpcClient.class);
    private final WindowSensors sut = new WindowSensors(registry, rpc, JsonMapper.builder().build(),
        new WindowProperties(Duration.ofMinutes(30)), new Clock() {
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
            return now.get().atZone(HOUSE).toInstant();
        }
    });

    //the cinema windows: one Eaton actuator, two sashes; the office blind has no sensor
    private final Blind cinema = blind(20, 67L, 68L);
    private final Blind bedroom = blind(21, 56L);
    private final Blind office = blind(52);

    private final List<SmartDevice> sensors = new ArrayList<>(List.of(
        sensor(67, "Cinema window left", LEFT, true),
        sensor(68, "Cinema window right", RIGHT, true),
        sensor(56, "Bedroom door", DOOR, true)));

    @BeforeEach
    void setUp() {
        when(registry.getOpeningSensors()).thenAnswer(call -> Mono.just(List.copyOf(sensors)));
        when(registry.getBthomeGateways()).thenReturn(Mono.just(List.of(
            gateway(54, "TREADMILL", "10.0.0.36", TREADMILL),
            gateway(80, "OFFICE MAIN", "10.0.0.40", OFFICE_MAIN))));
        when(rpc.dynamicComponents("10.0.0.40")).thenReturn(Mono.just(List.of()));
    }

    @Test
    void should_count_a_window_without_a_sensor_as_closed() {
        assertThat(sut.stateOf(office)).isEqualTo(WindowState.NO_SENSOR);
        assertThat(WindowState.NO_SENSOR.closed()).isTrue();
    }

    @Test
    void should_not_know_a_window_whose_sensor_never_reported() {
        gateway(TREADMILL, "10.0.0.36", window(202, LEFT, null, null));

        refresh();

        assertThat(sut.stateOf(cinema)).isEqualTo(WindowState.UNKNOWN);
        assertThat(sut.stateOf(bedroom)).isEqualTo(WindowState.UNKNOWN);
    }

    //every sash closed is a closed window; one sash open is an open one, whatever the other says
    @Test
    void should_close_a_window_only_when_every_sensor_is_closed() {
        gateway(TREADMILL, "10.0.0.36", window(202, LEFT, false, 1000L), window(206, RIGHT, false, 1000L));
        refresh();
        assertThat(sut.stateOf(cinema)).isEqualTo(WindowState.CLOSED);

        sut.observe(TREADMILL.toUpperCase(), 206, status(206, true, 1100L));
        assertThat(sut.stateOf(cinema)).isEqualTo(WindowState.OPEN);
    }

    //an open sash is open even while the other is not known
    @Test
    void should_open_a_window_with_one_sensor_open_and_one_not_known() {
        gateway(TREADMILL, "10.0.0.36", window(202, LEFT, true, 1000L), window(206, RIGHT, null, null));

        refresh();

        assertThat(sut.stateOf(cinema)).isEqualTo(WindowState.OPEN);
    }

    //a sensor sends only on a change: a quiet window stays closed as long as its gateway answers
    @Test
    void should_keep_a_state_while_the_gateway_confirms_it_and_lose_it_when_the_gateway_goes_silent() {
        gateway(TREADMILL, "10.0.0.36", window(215, DOOR, false, 1000L));
        refresh();

        now.set(START.plusHours(6));
        refresh();
        assertThat(sut.stateOf(bedroom)).isEqualTo(WindowState.CLOSED);

        when(rpc.dynamicComponents("10.0.0.36")).thenReturn(Mono.error(new IllegalStateException("no route to host")));
        now.set(START.plusHours(6).plusMinutes(25));
        refresh();
        assertThat(sut.stateOf(bedroom)).isEqualTo(WindowState.CLOSED);

        now.set(START.plusHours(6).plusMinutes(31));
        refresh();
        assertThat(sut.stateOf(bedroom)).isEqualTo(WindowState.UNKNOWN);

        //a publish of the gateway stands for it too
        sut.observe(TREADMILL, 215, status(215, false, 2000L));
        assertThat(sut.stateOf(bedroom)).isEqualTo(WindowState.CLOSED);
    }

    //only the window object (0x2D) of a registered sensor: the battery of the same sensor, a window
    //of a device nobody registered and a component without an id are none of the service's
    @Test
    void should_map_only_the_window_of_a_registered_sensor() {
        final ShellyComponent battery = component("bthomesensor:200", Map.of("id", 200, "addr", DOOR, "obj_id", 1, "idx", 0),
            Map.of("id", 200, "value", 78, "last_updated_ts", 1000));
        gateway(TREADMILL, "10.0.0.36", battery, window(219, "7c:c6:b6:00:00:99", true, 1000L), window(215, DOOR, false, 1000L));

        refresh();

        assertThat(sut.knows(TREADMILL, 215)).isTrue();
        assertThat(sut.knows(TREADMILL, 200)).isFalse();
        assertThat(sut.knows(TREADMILL, 219)).isFalse();
        assertThat(sut.knows(OFFICE_MAIN, 215)).isFalse();
        assertThat(sut.stateOf(bedroom)).isEqualTo(WindowState.CLOSED);
    }

    //a read of the gateway may bring an older state than the publish that came a moment before
    @Test
    void should_keep_the_newer_of_two_reports() {
        gateway(TREADMILL, "10.0.0.36", window(215, DOOR, false, 1000L));
        refresh();
        sut.observe(TREADMILL, 215, status(215, true, 1200L));

        gateway(TREADMILL, "10.0.0.36", window(215, DOOR, false, 1000L));
        refresh();

        assertThat(sut.stateOf(bedroom)).isEqualTo(WindowState.OPEN);
    }

    //a sensor moved to another gateway is found there with the next read, and the old component is forgotten
    @Test
    void should_follow_a_sensor_to_another_gateway() {
        gateway(TREADMILL, "10.0.0.36", window(215, DOOR, false, 1000L));
        refresh();

        gateway(TREADMILL, "10.0.0.36");
        gateway(OFFICE_MAIN, "10.0.0.40", window(202, DOOR, true, 1500L));
        refresh();

        assertThat(sut.knows(TREADMILL, 215)).isFalse();
        assertThat(sut.knows(OFFICE_MAIN, 202)).isTrue();
        assertThat(sut.stateOf(bedroom)).isEqualTo(WindowState.OPEN);
    }

    //a switched-off sensor is left out of its window; with none left the window has no sensor
    @Test
    void should_leave_a_switched_off_sensor_out_of_its_window() {
        sensors.set(1, sensor(68, "Cinema window right", RIGHT, false));
        gateway(TREADMILL, "10.0.0.36", window(202, LEFT, false, 1000L));
        refresh();

        assertThat(sut.stateOf(cinema)).isEqualTo(WindowState.CLOSED);
        assertThat(sut.stateOf(blind(30, 68L))).isEqualTo(WindowState.NO_SENSOR);
    }

    //a registry away keeps the sensors last read, and a refresh never fails
    @Test
    void should_keep_the_last_sensors_when_the_registry_cannot_be_read() {
        gateway(TREADMILL, "10.0.0.36", window(215, DOOR, false, 1000L));
        refresh();

        when(registry.getOpeningSensors()).thenReturn(Mono.error(new RegistryCallException("database-service answered 503")));
        sut.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(sut.stateOf(bedroom)).isEqualTo(WindowState.CLOSED);
    }

    //a value that is no truth is no window, but the gateway stood for the sensor
    @Test
    void should_ignore_a_value_that_is_no_truth() {
        gateway(TREADMILL, "10.0.0.36", window(215, DOOR, false, 1000L));
        refresh();

        sut.observe(TREADMILL, 215, status(215, 1, 1300L));

        assertThat(sut.stateOf(bedroom)).isEqualTo(WindowState.CLOSED);
    }

    private void refresh() {
        sut.refresh().as(StepVerifier::create).verifyComplete();
    }

    private void gateway(final String deviceId, final String address, final ShellyComponent... components) {
        when(rpc.dynamicComponents(address)).thenReturn(Mono.just(List.of(components)));
    }

    private static ShellyComponent window(final int id, final String address, final Boolean open, final Long updatedAt) {
        final Map<String, Object> status = new HashMap<>();
        status.put("id", id);
        if (open != null) {
            status.put("value", open);
            status.put("last_updated_ts", updatedAt);
        }
        return component("bthomesensor:" + id, Map.of("id", id, "addr", address, "obj_id", 45, "idx", 0), status);
    }

    private static ShellyComponent component(final String key, final Map<String, Object> config, final Map<String, Object> status) {
        return ShellyComponent.builder().key(key).config(new HashMap<>(config)).status(new HashMap<>(status)).build();
    }

    private static BthomeSensorStatus status(final int id, final Object value, final long updatedAt) {
        return BthomeSensorStatus.builder().id(id).value(value).lastUpdatedTs(updatedAt).build();
    }

    private static Blind blind(final long id, final Long... sensors) {
        return new Blind(id, "Blind " + id, RoomName.CINEMA, SmartVendor.EATON, new BlindBinding.Eaton((int) id),
            BlindsPlacement.EXTERNAL, false, List.of(sensors));
    }

    private static SmartDevice sensor(final long id, final String name, final String address, final boolean enabled) {
        return SmartDevice.builder().id(id).name(name).room(RoomName.CINEMA).kind(DeviceKind.OPENING_SENSOR)
            .vendor(SmartVendor.SHELLY).enabled(enabled).bthome(BthomeBinding.builder().address(address).build()).build();
    }

    private static SmartDevice gateway(final long id, final String name, final String address, final String deviceId) {
        return SmartDevice.builder().id(id).name(name).room(RoomName.BEDROOM).kind(DeviceKind.BTHOME_GATEWAY)
            .vendor(SmartVendor.SHELLY).enabled(true)
            .shelly(ShellyBinding.builder().address(address).generation(3).deviceId(deviceId).build()).build();
    }
}
