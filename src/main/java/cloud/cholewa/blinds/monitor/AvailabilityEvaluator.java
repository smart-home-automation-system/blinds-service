package cloud.cholewa.blinds.monitor;

import cloud.cholewa.blinds.config.MonitorProperties;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

//What the availability monitor makes of the Shelly blinds and the alerts already open (HAS-250): pure,
//pinned by AvailabilityEvaluatorTest - the rules of the monitor of light-service (HAS-264).
//- a blind is out once it has been unreachable for offlineAfter, since it was first seen unreachable;
//- out and not announced: an alert; announced and still out: a reminder every reminderEvery; announced and
//  back - known to be, never only not known to be out after a restart: an info; announced and no longer
//  watched (removed from the registry, or no longer a Shelly): forgotten without a word
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AvailabilityEvaluator {

    /**
     * A watched blind with when it was first seen unreachable - {@code null} while it is reachable or
     * its reachability is not known.
     *
     * @param known whether its reachability is known: after a restart nothing says the blind is back
     */
    public record Watched(long id, String name, String room, LocalDateTime since, boolean known) {
    }

    /** An alert the household had. */
    public record Alert(long id, String name, String room, LocalDateTime since, LocalDateTime lastAlertAt) {
    }

    /** What is due: new alerts, reminders, returns, and the alerts of blinds no longer watched. */
    public record Decision(List<Alert> alerts, List<Alert> reminders, List<Alert> returns, List<Alert> forgotten) {

        public boolean nothing() {
            return alerts.isEmpty() && reminders.isEmpty() && returns.isEmpty() && forgotten.isEmpty();
        }
    }

    public static Decision decide(final List<Watched> watched, final Map<Long, Alert> open, final LocalDateTime now,
                                  final MonitorProperties properties) {
        final List<Alert> alerts = new ArrayList<>();
        final List<Alert> reminders = new ArrayList<>();
        final List<Alert> returns = new ArrayList<>();

        for (final Watched blind : watched) {
            final boolean out = blind.since() != null && !blind.since().plus(properties.offlineAfter()).isAfter(now);
            final Alert alert = open.get(blind.id());
            if (out && alert == null) {
                alerts.add(new Alert(blind.id(), blind.name(), blind.room(), blind.since(), now));
            } else if (out && !alert.lastAlertAt().plus(properties.reminderEvery()).isAfter(now)) {
                reminders.add(alert);
            } else if (!out && alert != null && blind.known() && blind.since() == null) {
                returns.add(alert);
            }
        }
        final Set<Long> ids = watched.stream().map(Watched::id).collect(Collectors.toSet());
        final List<Alert> forgotten = open.values().stream().filter(alert -> !ids.contains(alert.id())).toList();
        return new Decision(alerts, reminders, returns, forgotten);
    }
}
