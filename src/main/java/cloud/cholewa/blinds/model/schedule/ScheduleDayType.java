package cloud.cholewa.blinds.model.schedule;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * The days a schedule runs on, by the calendar of the house ({@code DayType} of the SDK): {@code workday},
 * {@code day off} - a weekend, a holiday or a day off of the household - or {@code any}.
 */
public enum ScheduleDayType {
    WORKDAY("workday"),
    DAY_OFF("day off"),
    ANY("any");

    private final String value;

    ScheduleDayType(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    //exactly as written: anything else is a body that cannot be read, a 400
    @JsonCreator
    public static ScheduleDayType ofValue(final String value) {
        return Arrays.stream(values())
            .filter(constant -> constant.value.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unexpected value '" + value + "'"));
    }
}
