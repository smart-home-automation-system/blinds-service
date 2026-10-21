package cloud.cholewa.blinds.monitor;

import cloud.cholewa.blinds.config.MonitorProperties;
import cloud.cholewa.blinds.monitor.AvailabilityEvaluator.Alert;
import cloud.cholewa.blinds.monitor.AvailabilityEvaluator.Decision;
import cloud.cholewa.blinds.monitor.AvailabilityEvaluator.Watched;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//The rules of the availability monitor of the Shelly blinds (HAS-250)
class AvailabilityEvaluatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0);
    private static final MonitorProperties PROPERTIES = new MonitorProperties(Duration.ofMinutes(5), Duration.ofHours(1));

    @Test
    void should_alert_a_blind_unreachable_for_offline_after() {
        final Decision decision = decide(List.of(out(NOW.minusMinutes(5))), Map.of());

        assertThat(decision.alerts()).containsExactly(new Alert(52, "Office window", "office", NOW.minusMinutes(5), NOW));
    }

    @Test
    void should_not_alert_a_blind_unreachable_for_less() {
        assertThat(decide(List.of(out(NOW.minusMinutes(4).minusSeconds(59))), Map.of()).nothing()).isTrue();
    }

    @Test
    void should_remind_an_outage_every_reminder_every() {
        final Alert open = alert(NOW.minusHours(2), NOW.minusHours(1));

        assertThat(decide(List.of(out(NOW.minusHours(2))), Map.of(52L, open)).reminders()).containsExactly(open);
        assertThat(decide(List.of(out(NOW.minusHours(2))), Map.of(52L, alert(NOW.minusHours(2), NOW.minusMinutes(59))))
            .nothing()).isTrue();
    }

    @Test
    void should_announce_a_blind_that_answers_again() {
        final Alert open = alert(NOW.minusHours(2), NOW.minusHours(1));

        assertThat(decide(List.of(new Watched(52, "Office window", "office", null, true)), Map.of(52L, open)).returns())
            .containsExactly(open);
    }

    //after a restart nothing says the blind is back
    @Test
    void should_keep_the_alert_of_a_blind_whose_reachability_is_not_known() {
        final Decision decision = decide(List.of(new Watched(52, "Office window", "office", null, false)),
            Map.of(52L, alert(NOW.minusHours(2), NOW.minusHours(1))));

        assertThat(decision.nothing()).isTrue();
    }

    //after a restart the outage counts anew: a blind still out is not back in the minutes before it is out again
    @Test
    void should_keep_the_alert_of_a_blind_still_unreachable_but_not_long_enough_since_a_restart() {
        final Decision decision = decide(List.of(out(NOW.minusMinutes(1))),
            Map.of(52L, alert(NOW.minusHours(2), NOW.minusHours(1))));

        assertThat(decision.nothing()).isTrue();
    }

    @Test
    void should_forget_the_alert_of_a_blind_no_longer_watched() {
        final Alert open = alert(NOW.minusHours(2), NOW.minusMinutes(10));

        assertThat(decide(List.of(), Map.of(52L, open)).forgotten()).containsExactly(open);
    }

    private static Decision decide(final List<Watched> watched, final Map<Long, Alert> open) {
        return AvailabilityEvaluator.decide(watched, open, NOW, PROPERTIES);
    }

    private static Watched out(final LocalDateTime since) {
        return new Watched(52, "Office window", "office", since, true);
    }

    private static Alert alert(final LocalDateTime since, final LocalDateTime lastAlertAt) {
        return new Alert(52, "Office window", "office", since, lastAlertAt);
    }
}
