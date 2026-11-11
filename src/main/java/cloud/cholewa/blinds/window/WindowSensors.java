package cloud.cholewa.blinds.window;

import cloud.cholewa.blinds.config.WindowProperties;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.WindowState;
import cloud.cholewa.blinds.registry.DeviceRegistryClient;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.model.BthomeSensorConfig;
import cloud.cholewa.shelly.model.BthomeSensorStatus;
import cloud.cholewa.shelly.model.ShellyComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

//The window sensors of the blinds (HAS-290): Shelly BLU Door/Window sensors adopted by BTHome gateways,
//each a device of the kind opening sensor in the registry, linked to the blinds of its window there.
//- Which bthomesensor component of which gateway is the window (object 0x2D) of which sensor is read
//  from the gateways themselves - Shelly.GetComponents, every five minutes, the way weather-service
//  maps its station - with the state each one holds. One gateway per sensor is the rule of the
//  house; should two deliver one sensor, the newer report wins.
//- Between two reads the state comes from the status topic of the component, about once a change.
//- A sensor sends only when it changes (no beacon - owner, 2026-10-17), so its own silence proves
//  nothing: a state stands while its gateway confirms it, by a read or a publish, within
//  blinds.window.gateway-silent-after. A sensor that never reported, or whose gateway went silent,
//  is unknown - and a failing sensor never passes for a closed window.
//- A window is judged per blind, by the sensors the registry links to it: open when one reports open,
//  unknown when one is not known, closed when every one is closed; no sensor counts as closed.
//  A switched-off sensor is left out of its window, as if it were not there
//- The last reading of every sensor is stored (HAS-420, the copy of heating-service HAS-419; WindowSensorStore) and
//  read back at the start as not confirmed: the history a gateway value is compared with, never a state a schedule
//  acts on. On 2026-11-10 a grid outage restarted the gateways with this service, the gateways restored the "open"
//  half of short open/close pairs with its old time, and nothing here remembered the newer "closed"
@Slf4j
@Component
public class WindowSensors {

    //the BTHome object of a window or a door: true is open
    static final int WINDOW_OBJECT = 0x2D;
    private static final String SENSOR = "bthomesensor:";

    private final DeviceRegistryClient deviceRegistryClient;
    private final ShellyRpcClient shellyRpcClient;
    //a firmware adds fields to a config, and the models have only what is read here
    private final JsonMapper jsonMapper;
    private final WindowProperties properties;
    private final WindowSensorStore store;
    private final Clock clock;

    //the opening sensors of the registry by their id, as last read
    private final AtomicReference<Map<Long, Sensor>> sensors = new AtomicReference<>(Map.of());
    //the BTHome gateways of the registry, as last read
    private final AtomicReference<List<Gateway>> gateways = new AtomicReference<>(List.of());
    //which component of which gateway is the window of which sensor
    private final Map<SensorComponent, Long> components = new ConcurrentHashMap<>();
    private final Map<Long, Reading> readings = new ConcurrentHashMap<>();
    //the gateways the last read failed on: a WARN when one starts failing, an INFO when it is back
    private final Set<String> failing = ConcurrentHashMap.newKeySet();
    //the sensors a gateway gave an older value of since the start: an INFO once per sensor (HAS-420)
    private final Set<Long> olderTold = ConcurrentHashMap.newKeySet();
    //the sensors the stored readings were last trimmed to: the table is trimmed only when the registry lists others
    private final AtomicReference<Set<Long>> trimmedTo = new AtomicReference<>(Set.of());

    public WindowSensors(
        final DeviceRegistryClient deviceRegistryClient,
        final ShellyRpcClient shellyRpcClient,
        final JsonMapper jsonMapper,
        final WindowProperties properties,
        final WindowSensorStore store,
        final Clock clock
    ) {
        this.deviceRegistryClient = deviceRegistryClient;
        this.shellyRpcClient = shellyRpcClient;
        this.jsonMapper = jsonMapper.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        this.properties = properties;
        this.store = store;
        this.clock = clock;
    }

    /** An opening sensor of the registry: its Bluetooth address, null for one that is no BTHome sensor. */
    record Sensor(long id, String name, String address, boolean enabled) {
    }

    /** A bthomesensor component of a gateway, by the MQTT id of the gateway in lower case. */
    record SensorComponent(String gateway, int component) {
    }

    /**
     * What a sensor last said: {@code open} null until it said anything; {@code updatedAt} when the
     * gateway heard it; {@code confirmedAt} when a gateway last stood for it, null for a reading read back
     * from the table that no gateway has stood for since the start (HAS-420).
     */
    record Reading(Boolean open, Instant updatedAt, LocalDateTime confirmedAt) {
    }

    record Gateway(long id, String name, String address, String deviceId) {
    }

    //The stored readings, before the first read of the gateways (WindowReadingLoader): each one not confirmed, so not
    //known - it is only what a gateway value is compared with. Never fails; a reading already in memory is kept
    public Mono<Void> restore() {
        return store.load()
            .filter(stored -> readings.putIfAbsent(stored.sensorId(), stored.reading()) == null)
            .count()
            .doOnNext(count -> log.info("{} stored window sensor reading(s) restored, not known until a gateway stands for them", count))
            .then();
    }

    //Never fails. A registry away keeps the last sensors and gateways, and the gateways are read all
    //the same - a database-service down for an hour must not make every window unknown; a gateway
    //away keeps its map and lets its sensors age until they are unknown
    public Mono<Void> refresh() {
        return Mono.defer(() -> Mono.zip(deviceRegistryClient.getOpeningSensors(), deviceRegistryClient.getBthomeGateways()))
            .flatMap(read -> {
                final Map<Long, Sensor> listedSensors = toSensors(read.getT1());
                sensors.set(listedSensors);
                //a sensor the registry no longer lists is forgotten, a reading restored for it too
                readings.keySet().retainAll(listedSensors.keySet());
                final List<Gateway> listedNow = toGateways(read.getT2());
                gateways.set(listedNow);
                //a gateway the registry no longer lists is read no more: its components go with it
                final Set<String> listed = listedNow.stream().map(Gateway::deviceId).collect(Collectors.toSet());
                components.keySet().removeIf(key -> !listed.contains(key.gateway()));
                failing.retainAll(listed);
                return forgetTheGone(listedSensors.keySet()).thenReturn(listedNow);
            })
            .onErrorResume(e -> {
                log.warn("The window sensors could not be read from the registry, the last ones stay: {}", e.toString());
                return Mono.just(gateways.get());
            })
            .flatMapMany(Flux::fromIterable)
            .concatMap(this::read)
            .then();
    }

    private Mono<Void> read(final Gateway gateway) {
        return shellyRpcClient.dynamicComponents(gateway.address())
            .map(list -> {
                final List<Change> changes = apply(gateway, list);
                if (failing.remove(gateway.deviceId())) {
                    log.info("Gateway {} ({}) is read again", gateway.id(), gateway.name());
                }
                return changes;
            })
            .doOnError(e -> {
                if (failing.add(gateway.deviceId())) {
                    log.warn("Gateway {} ({}) could not be read, its window sensors are unknown once it has been silent for {}: {}",
                        gateway.id(), gateway.name(), properties.gatewaySilentAfter(), e.toString());
                }
            })
            .onErrorComplete()
            //the changes stored one after the other, each never failing: a write never stops the read of the next gateway
            .flatMapMany(Flux::fromIterable)
            .concatMap(this::storeChange)
            .then();
    }

    //the stored readings of the sensors gone from the registry go with them, once the registry lists others; never
    //fails. A registry that lists none keeps them: one empty answer must not wipe what a restart compares with
    private Mono<Void> forgetTheGone(final Set<Long> listed) {
        return Mono.just(listed)
            .filter(ids -> !ids.isEmpty() && !ids.equals(trimmedTo.getAndSet(ids)))
            .flatMap(store::retain);
    }

    /** A reading of a sensor that changed, to be stored. */
    record Change(long sensor, Reading reading) {
    }

    private Mono<Void> storeChange(final Change change) {
        return store.save(change.sensor(), change.reading());
    }

    //the window components of the gateway that belong to a sensor of the registry, each with what it
    //holds now; a component of another device, or of no window, is not this service's. Answers the
    //readings that changed, for the table
    List<Change> apply(final Gateway gateway, final List<ShellyComponent> list) {
        final Map<String, Sensor> byAddress = sensors.get().values().stream()
            .filter(sensor -> sensor.address() != null)
            .collect(Collectors.toMap(sensor -> sensor.address().toLowerCase(Locale.ROOT), sensor -> sensor, (a, b) -> a));
        final LocalDateTime now = now();
        final Map<SensorComponent, Long> found = new HashMap<>();
        final List<Change> changes = new ArrayList<>();

        list.stream()
            .filter(component -> component.getKey() != null && component.getKey().startsWith(SENSOR))
            .forEach(component -> as(component.getConfig(), BthomeSensorConfig.class)
                .filter(config -> config.getId() != null && Integer.valueOf(WINDOW_OBJECT).equals(config.getObjId()))
                .flatMap(config -> Optional.ofNullable(config.getAddr())
                    .map(address -> byAddress.get(address.toLowerCase(Locale.ROOT)))
                    .map(sensor -> {
                        found.put(new SensorComponent(gateway.deviceId(), config.getId()), sensor.id());
                        return sensor;
                    }))
                .ifPresent(sensor -> as(component.getStatus(), BthomeSensorStatus.class)
                    .ifPresentOrElse(
                        status -> report(gateway.deviceId(), sensor.id(), status, now).ifPresent(changes::add),
                        () -> confirm(sensor.id(), now))));

        components.keySet().removeIf(key -> key.gateway().equals(gateway.deviceId()) && !found.containsKey(key));
        components.putAll(found);
        return changes;
    }

    //is this status topic the window of a sensor - asked before a body is read
    public boolean knows(final String gatewayId, final Integer component) {
        return gatewayId != null && component != null
            && components.containsKey(new SensorComponent(gatewayId.toLowerCase(Locale.ROOT), component));
    }

    //the reading is taken at once; what the answer stores runs when it is subscribed to, and never fails - a failed
    //write never stops the listener
    public Mono<Void> observe(final String gatewayId, final int component, final BthomeSensorStatus status) {
        final String gateway = gatewayId.toLowerCase(Locale.ROOT);
        return Optional.ofNullable(components.get(new SensorComponent(gateway, component)))
            .flatMap(sensor -> report(gateway, sensor, status, now()))
            .map(this::storeChange)
            .orElseGet(Mono::empty);
    }

    //A value that is not a truth is not a window: the gateway stood for the sensor all the same.
    //Of two reports the one the gateway heard later counts - a read of the gateway may bring an older
    //state than a publish that came a moment before it, and a gateway restarted may bring back a state
    //older than the one stored before the restart (HAS-420).
    //Answers the reading to store: a new value, or the same value heard later - never a confirmation
    private Optional<Change> report(final String gateway, final long sensor, final BthomeSensorStatus status, final LocalDateTime now) {
        final Boolean open = status.getValue() instanceof Boolean value ? value : null;
        final Instant updatedAt = status.getLastUpdatedTs() == null ? null : Instant.ofEpochSecond(status.getLastUpdatedTs());
        final AtomicReference<Change> changed = new AtomicReference<>();
        readings.compute(sensor, (id, held) -> {
            if (open == null) {
                return held == null ? new Reading(null, null, now) : confirmedIfHeard(held, now);
            }
            if (held != null && held.updatedAt() != null && updatedAt != null && updatedAt.isBefore(held.updatedAt())) {
                tellOlder(gateway, sensor, open, updatedAt, held);
                return new Reading(held.open(), held.updatedAt(), now);
            }
            final Reading next = next(sensor, held, open, updatedAt, now);
            if (isChange(held, next)) {
                changed.set(new Change(sensor, next));
            }
            return next;
        });
        return Optional.ofNullable(changed.get());
    }

    //a value not older than the one held; another value than the held one is logged
    private Reading next(final long sensor, final Reading held, final boolean open, final Instant updatedAt, final LocalDateTime now) {
        if (held == null || !Boolean.valueOf(open).equals(held.open())) {
            log.info("Window sensor {} ({}): {}", sensor, nameOf(sensor), word(open));
        }
        return new Reading(open, updatedAt, now);
    }

    //a new value, or the same value heard later - never a confirmation of what is held
    private static boolean isChange(final Reading held, final Reading next) {
        return held == null || !Objects.equals(held.open(), next.open())
            || next.updatedAt() != null && !next.updatedAt().equals(held.updatedAt());
    }

    //once per sensor and start, so that the next restored state of a gateway shows in the logs
    private void tellOlder(final String gateway, final long sensor, final boolean open, final Instant updatedAt, final Reading held) {
        if (olderTold.add(sensor)) {
            log.info("Window sensor {} ({}): gateway {} gave {} heard at {}, older than the kept {} heard at {} - the kept one stays",
                sensor, nameOf(sensor), gateway, word(open), heardAt(updatedAt), word(held.open()), heardAt(held.updatedAt()));
        }
    }

    private static String word(final Boolean open) {
        return Boolean.TRUE.equals(open) ? "open" : "closed";
    }

    private LocalDateTime heardAt(final Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone());
    }

    //a component without a window value: the gateway stands for what it heard before, but a reading read back from the
    //table it never stood for stays not known - only a value of the gateway confirms that one (HAS-420)
    private void confirm(final long sensor, final LocalDateTime now) {
        readings.compute(sensor, (id, held) -> held == null ? new Reading(null, null, now) : confirmedIfHeard(held, now));
    }

    private static Reading confirmedIfHeard(final Reading held, final LocalDateTime now) {
        return held.confirmedAt() == null ? held : new Reading(held.open(), held.updatedAt(), now);
    }

    public WindowState stateOf(final Blind blind) {
        return stateOf(blind, now());
    }

    public WindowState stateOf(final Blind blind, final LocalDateTime now) {
        final Map<Long, Sensor> known = sensors.get();
        final List<Long> ids = blind.openingSensors().stream()
            .filter(id -> {
                final Sensor sensor = known.get(id);
                return sensor == null || sensor.enabled();
            })
            .toList();
        if (ids.isEmpty()) {
            return WindowState.NO_SENSOR;
        }

        final LocalDateTime oldest = now.minus(properties.gatewaySilentAfter());
        final List<WindowState> states = ids.stream()
            .map(readings::get)
            //a reading read back from the table is not known until a gateway stands for it (HAS-420)
            .map(reading -> reading == null || reading.open() == null || reading.confirmedAt() == null
                || reading.confirmedAt().isBefore(oldest)
                ? WindowState.UNKNOWN
                : reading.open() ? WindowState.OPEN : WindowState.CLOSED)
            .toList();
        if (states.contains(WindowState.OPEN)) {
            return WindowState.OPEN;
        }
        return states.contains(WindowState.UNKNOWN) ? WindowState.UNKNOWN : WindowState.CLOSED;
    }

    private String nameOf(final long sensor) {
        final Sensor known = sensors.get().get(sensor);
        return known == null ? "?" : known.name();
    }

    private static Map<Long, Sensor> toSensors(final List<SmartDevice> devices) {
        final Map<Long, Sensor> read = new HashMap<>();
        devices.stream()
            .filter(Objects::nonNull)
            .filter(device -> device.getId() != null)
            .forEach(device -> read.put(device.getId(), new Sensor(
                device.getId(),
                device.getName(),
                device.getBthome() == null ? null : device.getBthome().getAddress(),
                Boolean.TRUE.equals(device.getEnabled()))));
        return Map.copyOf(read);
    }

    //a gateway is a Shelly on the network with its MQTT id; one without either cannot be read
    private static List<Gateway> toGateways(final List<SmartDevice> devices) {
        return devices.stream()
            .filter(Objects::nonNull)
            .filter(device -> {
                final boolean usable = device.getId() != null && device.getShelly() != null
                    && device.getShelly().getAddress() != null && device.getShelly().getDeviceId() != null;
                if (!usable) {
                    log.warn("BTHome gateway {} ({}) of the registry has no address or MQTT id and is not read",
                        device.getId(), device.getName());
                }
                return usable;
            })
            .map(device -> new Gateway(device.getId(), device.getName(), device.getShelly().getAddress(),
                device.getShelly().getDeviceId().toLowerCase(Locale.ROOT)))
            .toList();
    }

    private <T> Optional<T> as(final Map<String, Object> raw, final Class<T> model) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jsonMapper.convertValue(raw, model));
        } catch (JacksonException | IllegalArgumentException notTheModel) {
            return Optional.empty();
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
