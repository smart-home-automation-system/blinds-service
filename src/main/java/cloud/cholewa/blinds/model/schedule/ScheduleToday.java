package cloud.cholewa.blinds.model.schedule;

import cloud.cholewa.blinds.engine.RunOutcome;
import cloud.cholewa.blinds.engine.ScheduleEvaluator.ConditionStatus;
import cloud.cholewa.blinds.engine.ScheduleEvaluator.Verdict;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * A schedule today, as the engine sees it: {@code GET /home/blinds/schedules/{id}/today}. What is not
 * known is left out - no {@code window} when an end is a time of the sun the day does not have, no
 * {@code dayType} before the calendar or the weekday was asked, no {@code current} for a metric
 * without a recent value.<br>
 * {@code dayTypeFromCalendar} - false when the calendar could not be read and the weekday decided.<br>
 * {@code decision} - what the schedule does in this minute ({@code fires}, {@code waiting for the
 * conditions}, {@code fired today} ...).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScheduleToday(
    long scheduleId,
    LocalDate date,
    String dayType,
    Boolean dayTypeFromCalendar,
    boolean runsToday,
    Window window,
    List<Condition> conditions,
    List<Target> targets,
    Verdict decision
) {

    private static final String MINUTES = "HH:mm";

    /** The window resolved for today, local time: {@code from} included, {@code to} not. */
    public record Window(
        @JsonFormat(pattern = MINUTES) LocalTime from,
        @JsonFormat(pattern = MINUTES) LocalTime to
    ) {
    }

    /**
     * A condition of the schedule and what it says now: {@code current} the value it was judged by,
     * {@code measuredAt} when the station had it, {@code trueSince} since when it has held.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Condition(
        ConditionMetric metric,
        ConditionOperator operator,
        BigDecimal threshold,
        Boolean value,
        int holdMinutes,
        Object current,
        LocalDateTime measuredAt,
        LocalDateTime trueSince,
        ConditionStatus state
    ) {
    }

    /**
     * A blind of the targets, or one the schedule moved today that the registry no longer knows
     * (then without a {@code name} and a {@code room}); {@code firedAt} and {@code outcome} once it
     * was moved today.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Target(
        long deviceId,
        String name,
        String room,
        LocalDateTime firedAt,
        RunOutcome outcome
    ) {
    }
}
