package cloud.cholewa.blinds.engine;

import cloud.cholewa.blinds.engine.ScheduleEvaluator.Truth;
import cloud.cholewa.blinds.model.schedule.ScheduleCondition;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

//Since when each condition of each schedule has been true without a break, in the memory of the one
//instance: a restart starts every hold again, which delays a schedule by its hold and never fires
//one early. A condition is known by its schedule and its content, so a schedule replaced with
//another threshold starts that hold again. Neither false nor unknown keeps a hold
@Component
public class HoldTracker {

    /**
     * A condition of a schedule; {@code blindId} - the blind a window closed condition is judged for
     * (HAS-290), null for a condition of the schedule as a whole.
     */
    public record Key(long scheduleId, ScheduleCondition condition, Long blindId) {

        public Key(final long scheduleId, final ScheduleCondition condition) {
            this(scheduleId, condition, null);
        }
    }

    private final Map<Key, LocalDateTime> trueSince = new ConcurrentHashMap<>();

    //the pass: what the condition says now; answers since when it has been true, null when it is not
    public LocalDateTime observe(final Key key, final Truth truth, final LocalDateTime now) {
        if (truth != Truth.TRUE) {
            trueSince.remove(key);
            return null;
        }
        return trueSince.computeIfAbsent(key, ignored -> now);
    }

    //the reads: what the last pass saw, changing nothing
    public LocalDateTime since(final Key key) {
        return trueSince.get(key);
    }

    //the conditions of schedules gone or replaced are forgotten
    public void retain(final Set<Key> keys) {
        trueSince.keySet().retainAll(keys);
    }
}
