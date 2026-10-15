package cloud.cholewa.blinds.registry;

import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartDevice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//The blinds the service drives: the switched-on blinds of the device registry, read at the start and
//every five minutes (BlindRegistryRefresh). A failed refresh keeps the last list - a database-service
//that is away for a moment must not make every blind unknown. Until the first refresh succeeds a
//lookup reads the registry itself, so a command right after a start is not refused because the pod
//came up before database-service
@Slf4j
@Component
@RequiredArgsConstructor
public class BlindRegistry {

    private final DeviceRegistryClient deviceRegistryClient;

    //null until the first successful read; never null afterwards
    private final AtomicReference<List<Blind>> blinds = new AtomicReference<>();

    public Mono<Void> refresh() {
        return load()
            .doOnError(e -> log.warn("Refresh of the blinds failed, the last list stays: {}", e.toString()))
            .onErrorComplete()
            .then();
    }

    //logged whenever the list changes, not only its size: a blind moved to another room or address
    //must leave a trace of when the service learned it
    private Mono<List<Blind>> load() {
        return Mono.defer(deviceRegistryClient::getBlinds)
            .map(BlindRegistry::toBlinds)
            .doOnNext(read -> {
                final List<Blind> previous = blinds.getAndSet(read);
                if (!read.equals(previous)) {
                    log.info("Blinds known from the device registry: {}", read.size());
                }
            });
    }

    public Mono<Blind> find(final long id) {
        return known()
            .flatMap(list -> Mono.justOrEmpty(list.stream().filter(blind -> blind.id() == id).findFirst()))
            .switchIfEmpty(Mono.error(() -> BlindsException.notFound("no blind with id " + id)));
    }

    public Mono<List<Blind>> inRoom(final RoomName room) {
        return known()
            .map(list -> list.stream().filter(blind -> blind.room() == room).toList())
            .filter(list -> !list.isEmpty())
            .switchIfEmpty(Mono.error(() -> BlindsException.notFound("no blind in the room " + room.getValue())));
    }

    //never read: an unknown blind would be a lie, so the caller is told the registry is away
    private Mono<List<Blind>> known() {
        return Mono.defer(() -> {
            final List<Blind> list = blinds.get();
            return list != null
                ? Mono.just(list)
                : load().onErrorMap(e -> !(e instanceof BlindsException), BlindsException::registryUnavailable);
        });
    }

    private static List<Blind> toBlinds(final List<SmartDevice> devices) {
        return devices.stream()
            .filter(Objects::nonNull)
            .map(device -> {
                final Optional<Blind> blind = BlindMapper.toBlind(device);
                if (blind.isEmpty()) {
                    log.warn("Device {} ({}) of the registry cannot be driven as a blind and is skipped",
                        device.getId(), device.getName());
                }
                return blind;
            })
            .flatMap(Optional::stream)
            .toList();
    }
}
