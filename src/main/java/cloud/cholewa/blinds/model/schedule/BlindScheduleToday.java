package cloud.cholewa.blinds.model.schedule;

import cloud.cholewa.blinds.engine.RunOutcome;
import cloud.cholewa.blinds.engine.ScheduleEvaluator.Verdict;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * A schedule that moves one blind, as the engine sees it now (HAS-232):
 * {@code GET /home/blinds/{id}/schedules} - what the room view shows next to a blind.<br>
 * {@code window} - today's window, left out when an end is a time of the sun the day does not have;
 * {@code decision} - what the schedule does in this minute, for all its blinds;
 * {@code firedAt} / {@code outcome} - when it ran for this blind in its current window and what came
 * of it ({@code paused} when the pause of the blind held it back), left out until it has.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BlindScheduleToday(
    long scheduleId,
    String name,
    boolean enabled,
    ScheduleAction action,
    Integer position,
    ScheduleToday.Window window,
    Verdict decision,
    LocalDateTime firedAt,
    RunOutcome outcome
) {
}
