package cloud.cholewa.blinds.model.schedule;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** What a schedule does to its targets: {@code open}, {@code close}, or send them to a {@code position}. */
public enum ScheduleAction {
    OPEN("open"),
    CLOSE("close"),
    POSITION("position");

    private final String value;

    ScheduleAction(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    //exactly as written: anything else is a body that cannot be read, a 400
    @JsonCreator
    public static ScheduleAction ofValue(final String value) {
        return Arrays.stream(values())
            .filter(constant -> constant.value.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unexpected value '" + value + "'"));
    }
}
