package cloud.cholewa.blinds.monitor;

import cloud.cholewa.blinds.config.MonitorProperties;
import cloud.cholewa.blinds.database.model.AvailabilityAlertEntity;
import cloud.cholewa.blinds.database.repository.AvailabilityAlertRepository;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.BlindStatus;
import cloud.cholewa.blinds.registry.BlindRegistry;
import cloud.cholewa.blinds.state.BlindStateTracker;
import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.home.model.BlindsPlacement;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The availability monitor around its evaluator (HAS-250): what it watches, one message for many blinds, the
//table moved only once the broker took the message
class AvailabilityMonitorTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 21, 12, 0);
    private static final Blind WINDOW = new Blind(52, "Office window", RoomName.OFFICE, SmartVendor.SHELLY,
        new BlindBinding.ShellyGen1("10.0.0.51", 0), BlindsPlacement.EXTERNAL, true);
    private static final Blind DOOR = new Blind(53, "Office door", RoomName.OFFICE, SmartVendor.SHELLY,
        new BlindBinding.ShellyGen2("10.0.0.50", 0, "shellyplus2pm-a1"), BlindsPlacement.EXTERNAL, true);
    private static final Blind KITCHEN = new Blind(9, "Kitchen", RoomName.KITCHEN, SmartVendor.EATON,
        new BlindBinding.Eaton(9), BlindsPlacement.EXTERNAL, false);

    private final AtomicReference<LocalDateTime> now = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
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
    };

    private final BlindRegistry blindRegistry = mock(BlindRegistry.class);
    private final BlindStateTracker blindStateTracker = mock(BlindStateTracker.class);
    private final AvailabilityAlertRepository alertRepository = mock(AvailabilityAlertRepository.class);
    private final NotificationPublisher notificationPublisher = mock(NotificationPublisher.class);
    private AvailabilityMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new AvailabilityMonitor(blindRegistry, blindStateTracker, alertRepository, notificationPublisher,
            new MonitorProperties(Duration.ofMinutes(5), Duration.ofHours(1)), clock);
        when(blindRegistry.loaded()).thenReturn(true);
        when(blindRegistry.current()).thenReturn(List.of(WINDOW, DOOR, KITCHEN));
        when(blindStateTracker.status(anyLong())).thenReturn(Mono.just(reachable(true)));
        when(alertRepository.findAll()).thenReturn(Flux.empty());
        when(alertRepository.open(anyLong(), anyString(), anyString(), any(), any())).thenReturn(Mono.just(1));
        when(alertRepository.deleteById(anyLong())).thenReturn(Mono.empty());
        when(alertRepository.reminded(anyLong(), any())).thenReturn(Mono.just(1));
        when(notificationPublisher.publishAlert(anyString())).thenReturn(Mono.empty());
        when(notificationPublisher.publishInfo(anyString())).thenReturn(Mono.empty());
        when(notificationPublisher.publishReminder(anyString())).thenReturn(Mono.empty());
    }

    //two Shelly blinds out together: one alert naming both, two rows; the Eaton blind is never asked
    @Test
    void should_announce_every_blind_out_in_one_alert() {
        when(blindStateTracker.status(52)).thenReturn(Mono.just(reachable(false)));
        when(blindStateTracker.status(53)).thenReturn(Mono.just(reachable(false)));

        check();
        now.set(START.plusMinutes(5));
        check();

        verify(notificationPublisher, times(1)).publishAlert(
            "Not available: the blind Office window (office, 52) does not answer since 2026-10-21 12:00 (5 min); "
                + "the blind Office door (office, 53) does not answer since 2026-10-21 12:00 (5 min).");
        verify(alertRepository).open(52, "Office window", "office", START, START.plusMinutes(5));
        verify(alertRepository).open(53, "Office door", "office", START, START.plusMinutes(5));
        verify(blindStateTracker, never()).status(9);
    }

    //a blind that answered between two passes counts anew
    @Test
    void should_count_an_outage_from_the_last_time_the_blind_was_seen_unreachable_after_an_answer() {
        when(blindStateTracker.status(52)).thenReturn(Mono.just(reachable(false)), Mono.just(reachable(true)),
            Mono.just(reachable(false)));

        check();
        now.set(START.plusMinutes(3));
        check();
        now.set(START.plusMinutes(6));
        check();

        verify(notificationPublisher, never()).publishAlert(anyString());
    }

    //the broker away: nothing written, the alert tried again with the next pass
    @Test
    void should_write_nothing_when_the_alert_is_not_published() {
        when(blindStateTracker.status(52)).thenReturn(Mono.just(reachable(false)));
        when(notificationPublisher.publishAlert(anyString())).thenReturn(Mono.error(new IllegalStateException("broker away")));
        check();
        now.set(START.plusMinutes(5));

        check();
        now.set(START.plusMinutes(6));
        check();

        verify(notificationPublisher, times(2)).publishAlert(anyString());
        verify(alertRepository, never()).open(anyLong(), anyString(), anyString(), any(), any());
    }

    //a reachability not known after a start is not judged
    @Test
    void should_not_judge_a_blind_whose_reachability_is_not_known() {
        when(blindStateTracker.status(52)).thenReturn(Mono.just(BlindStatus.unknown()));
        check();
        now.set(START.plusHours(1));

        check();

        verify(notificationPublisher, never()).publishAlert(anyString());
    }

    @Test
    void should_announce_a_return_and_close_the_alert() {
        when(alertRepository.findAll()).thenReturn(Flux.just(alertRow(START.minusHours(1), START.minusMinutes(30))));

        check();

        verify(notificationPublisher).publishInfo(
            "Available again: the blind Office window (office, 52) - out from 2026-10-21 11:00 to 2026-10-21 12:00 (1 h 0 min).");
        verify(alertRepository).deleteById(52L);
    }

    //the first pass after a restart: still out, not yet five minutes in this pod - neither back nor reminded
    @Test
    void should_not_announce_a_return_of_a_blind_still_out_after_a_restart() {
        when(blindStateTracker.status(52)).thenReturn(Mono.just(reachable(false)));
        when(alertRepository.findAll()).thenReturn(Flux.just(alertRow(START.minusHours(2), START.minusHours(1))));

        check();

        verify(notificationPublisher, never()).publishInfo(anyString());
        verify(notificationPublisher, never()).publishReminder(anyString());
        verify(alertRepository, never()).deleteById(anyLong());
    }

    @Test
    void should_remind_an_outage_after_an_hour() {
        when(blindStateTracker.status(52)).thenReturn(Mono.just(reachable(false)));
        when(alertRepository.findAll()).thenReturn(Flux.just(alertRow(START.minusHours(2), START.minusHours(1))));
        check();
        now.set(START.plusMinutes(5));

        check();

        verify(notificationPublisher).publishReminder(
            "Still not available: the blind Office window (office, 52) does not answer since 2026-10-21 10:00 (2 h 5 min).");
        verify(alertRepository).reminded(52L, START.plusMinutes(5));
    }

    //a blind removed from the registry: its alert is closed without a word
    @Test
    void should_close_the_alert_of_a_blind_no_longer_in_the_registry() {
        when(blindRegistry.current()).thenReturn(List.of(DOOR));
        when(alertRepository.findAll()).thenReturn(Flux.just(alertRow(START.minusHours(1), START.minusMinutes(30))));

        check();

        verify(alertRepository).deleteById(52L);
        verify(notificationPublisher, never()).publishInfo(anyString());
    }

    //before the registry was read every blind would look removed
    @Test
    void should_do_nothing_before_the_registry_was_read() {
        when(blindRegistry.loaded()).thenReturn(false);

        check();

        verify(alertRepository, never()).findAll();
    }

    //a Wi-Fi outage: ten names and the rest counted - a Discord embed holds 4096 characters
    @Test
    void should_name_at_most_ten_blinds() {
        final List<AvailabilityEvaluator.Alert> alerts = LongStream.rangeClosed(1, 12)
            .mapToObj(id -> new AvailabilityEvaluator.Alert(id, "Blind " + id, "office", START, START))
            .toList();

        assertThat(monitor.alertText(alerts, START.plusMinutes(5))).endsWith("; and 2 more.");
    }

    //the pass never fails: what failed is logged and the next pass runs
    @Test
    void should_complete_when_the_table_cannot_be_read() {
        when(alertRepository.findAll()).thenReturn(Flux.error(new IllegalStateException("no connection")));

        monitor.check().as(StepVerifier::create).verifyComplete();
        verify(notificationPublisher, never()).publishAlert(anyString());
        verify(alertRepository, never()).open(eq(52L), anyString(), anyString(), any(), any());
    }

    private void check() {
        monitor.check().as(StepVerifier::create).verifyComplete();
    }

    private static BlindStatus reachable(final boolean reachable) {
        return BlindStatus.unknown().withReachable(reachable);
    }

    private static AvailabilityAlertEntity alertRow(final LocalDateTime since, final LocalDateTime lastAlertAt) {
        return new AvailabilityAlertEntity(52L, "Office window", "office", since, lastAlertAt);
    }
}
