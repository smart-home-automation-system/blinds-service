package cloud.cholewa.blinds.model.schedule;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * What an end of the window of a schedule is: a {@code clock} time, or the {@code sunrise} or
 * {@code sunset} of the day.
 */
public enum WindowKind {
    CLOCK("clock"),
    SUNRISE("sunrise"),
    SUNSET("sunset");

    private final String value;

    WindowKind(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    //exactly as written: anything else is a body that cannot be read, a 400
    @JsonCreator
    public static WindowKind ofValue(final String value) {
        return Arrays.stream(values())
            .filter(constant -> constant.value.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unexpected value '" + value + "'"));
    }
}
