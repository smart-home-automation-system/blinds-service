package cloud.cholewa.blinds.model.schedule;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * What a condition of a schedule looks at: the {@code illuminance} (lx), the {@code outside temperature}
 * (°C) and the {@code wind gust} (m/s) of the weather station, whether it is {@code raining}, and whether
 * the window of the room of the target is closed ({@code window closed}).
 */
public enum ConditionMetric {
    ILLUMINANCE("illuminance"),
    OUTSIDE_TEMPERATURE("outside temperature"),
    WIND_GUST("wind gust"),
    RAINING("raining"),
    WINDOW_CLOSED("window closed");

    private final String value;

    ConditionMetric(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    //exactly as written: anything else is a body that cannot be read, a 400
    @JsonCreator
    public static ConditionMetric ofValue(final String value) {
        return Arrays.stream(values())
            .filter(constant -> constant.value.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unexpected value '" + value + "'"));
    }
}
