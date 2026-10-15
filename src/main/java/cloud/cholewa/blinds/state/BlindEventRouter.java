package cloud.cholewa.blinds.state;

import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.registry.BlindRegistry;
import cloud.cholewa.home.model.DeviceEvent;
import cloud.cholewa.home.model.EatonRxEvent;
import cloud.cholewa.shelly.codec.ShellyEvent;
import cloud.cholewa.shelly.codec.ShellyMqttDecoder;
import cloud.cholewa.shelly.codec.ShellyTopic;
import cloud.cholewa.shelly.model.CoverStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

//Which blind an event of the device queue is about, and what it says. The queue carries every event
//of every device: the routing key alone decides first, by the registry as last read, so the body of
//an event that is not about a blind is never read.
//- eaton.blinds.<point>: the DeviceEvent of amx-service (HAS-229); only its status is a state. The
//  group point 26 (ALL) is not registered, so amx-service publishes nothing for it - and the
//  actuator of the cinema reports on it too, so it could not stand for every blind anyway
//- <deviceId>.status.cover:<channel> and <deviceId>.online of a Gen2+ blind, decoded by shelly-client.
//A body that is not what its topic carries is an error for the consumer to log
@Slf4j
@Component
public class BlindEventRouter {

    private static final String EATON_BLINDS = "eaton.blinds.";
    private static final String COVER = "cover";

    private final BlindRegistry blindRegistry;
    private final BlindStateTracker blindStateTracker;
    private final ShellyMqttDecoder shellyMqttDecoder;
    private final JsonMapper jsonMapper;

    public BlindEventRouter(
        final BlindRegistry blindRegistry,
        final BlindStateTracker blindStateTracker,
        final ShellyMqttDecoder shellyMqttDecoder,
        final JsonMapper jsonMapper
    ) {
        this.blindRegistry = blindRegistry;
        this.blindStateTracker = blindStateTracker;
        this.shellyMqttDecoder = shellyMqttDecoder;
        this.jsonMapper = jsonMapper;
    }

    public Mono<Void> route(final String routingKey, final byte[] body) {
        if (routingKey == null) {
            return Mono.empty();
        }
        if (routingKey.startsWith(EATON_BLINDS)) {
            return eaton(routingKey, body);
        }
        return ShellyTopic.parse(routingKey)
            .map(topic -> shelly(topic, body))
            .orElseGet(Mono::empty);
    }

    private Mono<Void> eaton(final String routingKey, final byte[] body) {
        final Integer point = point(routingKey.substring(EATON_BLINDS.length()));
        final Blind blind = point == null ? null : blindRegistry.current().stream()
            .filter(candidate -> candidate.binding() instanceof BlindBinding.Eaton eaton && eaton.point() == point)
            .findFirst()
            .orElse(null);
        if (blind == null) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> jsonMapper.readValue(body, DeviceEvent.class))
            .filter(event -> event.getEvent() == EatonRxEvent.STATUS)
            .flatMap(event -> Mono.justOrEmpty(BlindReadings.ofEatonStatus(event.getValue()))
                .switchIfEmpty(Mono.fromRunnable(() -> log.warn("Blind {} ({}) reported the status {}, which is none of a blind",
                    blind.id(), blind.name(), event.getValue()))))
            .flatMap(reading -> blindStateTracker.observe(blind, reading));
    }

    private Mono<Void> shelly(final ShellyTopic topic, final byte[] body) {
        final List<Blind> blinds = blindRegistry.current().stream()
            .filter(blind -> blind.binding() instanceof BlindBinding.ShellyGen2 cover
                && topic.deviceId().equals(cover.deviceId()))
            .toList();
        if (blinds.isEmpty()) {
            return Mono.empty();
        }

        return switch (topic.kind()) {
            case ONLINE -> Mono.fromCallable(() -> shellyMqttDecoder.decode(topic, body))
                .flatMap(Mono::justOrEmpty)
                .cast(ShellyEvent.Online.class)
                .flatMapMany(online -> Flux.fromIterable(blinds)
                    .concatMap(blind -> blindStateTracker.reachability(blind, online.online(), "the device left the broker")))
                .then();
            case STATUS -> COVER.equals(topic.component()) ? cover(topic, body, blinds) : Mono.empty();
            default -> Mono.empty();
        };
    }

    private Mono<Void> cover(final ShellyTopic topic, final byte[] body, final List<Blind> blinds) {
        final Blind blind = blinds.stream()
            .filter(candidate -> topic.index() != null
                && ((BlindBinding.ShellyGen2) candidate.binding()).channel() == topic.index())
            .findFirst()
            .orElse(null);
        if (blind == null) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> shellyMqttDecoder.decode(topic, body))
            .flatMap(Mono::justOrEmpty)
            .cast(ShellyEvent.Status.class)
            .flatMap(status -> Mono.justOrEmpty(status.as(CoverStatus.class)))
            .flatMap(cover -> Mono.justOrEmpty(BlindReadings.ofCover(cover)))
            .flatMap(reading -> blindStateTracker.observe(blind, reading));
    }

    private static Integer point(final String text) {
        return !text.isEmpty() && text.length() <= 2 && text.chars().allMatch(Character::isDigit)
            ? Integer.valueOf(text)
            : null;
    }
}
