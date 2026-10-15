package cloud.cholewa.blinds.model.schedule;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** How a condition compares: {@code lt} and {@code gt} with a threshold, {@code is} with a truth. */
public enum ConditionOperator {
    LT("lt"),
    GT("gt"),
    IS("is");

    private final String value;

    ConditionOperator(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    //exactly as written: anything else is a body that cannot be read, a 400
    @JsonCreator
    public static ConditionOperator ofValue(final String value) {
        return Arrays.stream(values())
            .filter(constant -> constant.value.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unexpected value '" + value + "'"));
    }
}
