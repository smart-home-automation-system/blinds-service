package cloud.cholewa.blinds.model.schedule;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalTime;

/**
 * An end of the window of a schedule: a {@code clock} time, or the {@code sunrise} or {@code sunset}
 * of the day moved by {@code offsetMinutes} (-180..180) and held between the optional
 * {@code earliest} and {@code latest}. Times are {@code HH:mm}, both ways - a time with seconds is a
 * body that cannot be read.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WindowEnd(
    WindowKind kind,
    @JsonFormat(pattern = MINUTES)
    LocalTime time,
    Integer offsetMinutes,
    @JsonFormat(pattern = MINUTES)
    LocalTime earliest,
    @JsonFormat(pattern = MINUTES)
    LocalTime latest
) {

    private static final String MINUTES = "HH:mm";

    public static WindowEnd clock(final LocalTime time) {
        return new WindowEnd(WindowKind.CLOCK, time, null, null, null);
    }

    public static WindowEnd sun(final WindowKind kind, final int offsetMinutes, final LocalTime earliest,
        final LocalTime latest) {
        return new WindowEnd(kind, null, offsetMinutes, earliest, latest);
    }
}
