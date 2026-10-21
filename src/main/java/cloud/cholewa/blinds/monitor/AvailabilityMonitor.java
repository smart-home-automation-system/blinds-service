package cloud.cholewa.blinds.monitor;

import cloud.cholewa.blinds.config.MonitorProperties;
import cloud.cholewa.blinds.database.model.AvailabilityAlertEntity;
import cloud.cholewa.blinds.database.repository.AvailabilityAlertRepository;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.monitor.AvailabilityEvaluator.Alert;
import cloud.cholewa.blinds.monitor.AvailabilityEvaluator.Decision;
import cloud.cholewa.blinds.monitor.AvailabilityEvaluator.Watched;
import cloud.cholewa.blinds.registry.BlindRegistry;
import cloud.cholewa.blinds.state.BlindStateTracker;
import cloud.cholewa.commons.notification.NotificationPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

//The availability monitor (HAS-250): every minute the Shelly blinds are judged by AvailabilityEvaluator
//against the alerts stored in blinds_availability_alert, and what is due goes to the household through
//NotificationPublisher - one alert, one reminder and one info a pass at most, each naming every blind, so
//that a Wi-Fi outage is one message and not twelve. The shape of the monitor of light-service (HAS-264).
//- A blind is unreachable as BlindStateTracker says: a Gen1 roller by a failed poll of /status, a Gen2+
//  cover by its MQTT last will - the HTTP reconciliation never changes it. A reachability not known (after a
//  start) is not judged. The Eaton blinds are not watched: there is no device to ask, amx-service and the
//  frame server have their own health.
//- The table changes only once the broker has taken the message; a notification that fails is tried again
//  with the next pass, and neither fails nor stalls the pass for longer than PUBLISH_TIMEOUT
@Slf4j
@Component
public class AvailabilityMonitor {

    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_LISTED = 10;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BlindRegistry blindRegistry;
    private final BlindStateTracker blindStateTracker;
    private final AvailabilityAlertRepository alertRepository;
    private final NotificationPublisher notificationPublisher;
    private final MonitorProperties properties;
    private final Clock clock;

    //when each blind was first seen unreachable, in memory: a restart counts anew, the table keeps the alert
    private final Map<Long, LocalDateTime> unreachableSince = new ConcurrentHashMap<>();

    public AvailabilityMonitor(
        final BlindRegistry blindRegistry,
        final BlindStateTracker blindStateTracker,
        final AvailabilityAlertRepository alertRepository,
        final NotificationPublisher notificationPublisher,
        final MonitorProperties properties,
        final Clock clock
    ) {
        this.blindRegistry = blindRegistry;
        this.blindStateTracker = blindStateTracker;
        this.alertRepository = alertRepository;
        this.notificationPublisher = notificationPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    //Spring subscribes again to the same Mono for every run: everything is read inside the chain. Two minutes
    //in: the registry is read by then, and a Gen1 poll has run
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1M")
    Mono<Void> check() {
        return Mono.defer(() -> {
                //before the registry was read every blind would look removed, and its alert be forgotten
                if (!blindRegistry.loaded()) {
                    return Mono.empty();
                }
                final LocalDateTime now = LocalDateTime.now(clock);
                return watched(now)
                    .zipWith(alertRepository.findAll().collectMap(AvailabilityAlertEntity::deviceId, AvailabilityMonitor::toAlert))
                    .flatMap(read -> act(AvailabilityEvaluator.decide(read.getT1(), read.getT2(), now, properties), now));
            })
            .onErrorResume(e -> {
                log.error("The availability check failed: {}", e.toString());
                return Mono.empty();
            });
    }

    private Mono<List<Watched>> watched(final LocalDateTime now) {
        final List<Blind> shelly = blindRegistry.current().stream()
            .filter(blind -> !(blind.binding() instanceof BlindBinding.Eaton))
            .toList();
        unreachableSince.keySet().retainAll(shelly.stream().map(Blind::id).collect(Collectors.toSet()));
        return Flux.fromIterable(shelly)
            .concatMap(blind -> blindStateTracker.status(blind.id()).map(status -> {
                if (Boolean.FALSE.equals(status.reachable())) {
                    unreachableSince.putIfAbsent(blind.id(), now);
                } else {
                    unreachableSince.remove(blind.id());
                }
                return new Watched(blind.id(), blind.name(), blind.room().getValue(), unreachableSince.get(blind.id()),
                    status.reachable() != null);
            }))
            .collectList();
    }

    //returns first, then the alerts, then the reminders, each after the one before; the table follows the broker
    private Mono<Void> act(final Decision decision, final LocalDateTime now) {
        if (decision.nothing()) {
            return Mono.empty();
        }
        return Flux.fromIterable(decision.forgotten())
            .concatMap(alert -> alertRepository.deleteById(alert.id())
                .doOnSuccess(unused -> log.info("Availability alert of blind {} ({}) closed: it is no longer watched",
                    alert.id(), alert.name())))
            .then(publish(decision.returns(), notificationPublisher::publishInfo, returnText(decision.returns(), now))
                .flatMap(sent -> Flux.fromIterable(decision.returns()).concatMap(alert -> alertRepository.deleteById(alert.id())).then()))
            .then(publish(decision.alerts(), notificationPublisher::publishAlert, alertText(decision.alerts(), now))
                .flatMap(sent -> Flux.fromIterable(decision.alerts())
                    .concatMap(alert -> alertRepository.open(alert.id(), Objects.requireNonNullElse(alert.name(), "?"),
                        alert.room(), truncated(alert.since()), truncated(now)))
                    .then()))
            .then(publish(decision.reminders(), notificationPublisher::publishReminder, reminderText(decision.reminders(), now))
                .flatMap(sent -> Flux.fromIterable(decision.reminders())
                    .concatMap(alert -> alertRepository.reminded(alert.id(), truncated(now)))
                    .then()));
    }

    //Mono.just(true) once the broker took it; empty when there was nothing or it failed (logged, tried again)
    private Mono<Boolean> publish(final List<Alert> alerts, final Function<String, Mono<Void>> publisher, final String text) {
        if (alerts.isEmpty()) {
            return Mono.empty();
        }
        return publisher.apply(text)
            .timeout(PUBLISH_TIMEOUT)
            .thenReturn(true)
            .doOnNext(sent -> log.info("Availability notification published: {}", text))
            .onErrorResume(e -> {
                log.error("Availability notification not published, it is tried again with the next pass: {}", e.toString());
                return Mono.empty();
            });
    }

    String alertText(final List<Alert> alerts, final LocalDateTime now) {
        return "Not available: " + list(alerts, alert -> describe(alert) + " does not answer since " + time(alert.since())
            + " (" + span(alert.since(), now) + ")") + ".";
    }

    String reminderText(final List<Alert> alerts, final LocalDateTime now) {
        return "Still not available: " + list(alerts, alert -> describe(alert) + " does not answer since " + time(alert.since())
            + " (" + span(alert.since(), now) + ")") + ".";
    }

    String returnText(final List<Alert> alerts, final LocalDateTime now) {
        return "Available again: " + list(alerts, alert -> describe(alert) + " - out from " + time(alert.since()) + " to "
            + time(now) + " (" + span(alert.since(), now) + ")") + ".";
    }

    private static String describe(final Alert alert) {
        return "the blind " + alert.name() + " (" + alert.room() + ", " + alert.id() + ")";
    }

    //at most MAX_LISTED blinds by name, the rest counted: a Discord embed holds 4096 characters
    private static String list(final List<Alert> alerts, final Function<Alert, String> line) {
        final String listed = alerts.stream().limit(MAX_LISTED).map(line).collect(Collectors.joining("; "));
        return alerts.size() > MAX_LISTED ? listed + "; and " + (alerts.size() - MAX_LISTED) + " more" : listed;
    }

    private static String time(final LocalDateTime time) {
        return TIME.format(time);
    }

    private static String span(final LocalDateTime since, final LocalDateTime until) {
        //the minutes of the wall clock, as the times the message names
        final long total = (until.toEpochSecond(ZoneOffset.UTC) - since.toEpochSecond(ZoneOffset.UTC)) / 60;
        final long hours = total / 60;
        final long minutes = total % 60;
        return hours == 0 ? minutes + " min" : hours + " h " + minutes + " min";
    }

    private static Alert toAlert(final AvailabilityAlertEntity row) {
        return new Alert(row.deviceId(), row.name(), row.room(), row.since(), row.lastAlertAt());
    }

    private static LocalDateTime truncated(final LocalDateTime time) {
        return time.truncatedTo(ChronoUnit.MICROS);
    }
}
