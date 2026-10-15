package cloud.cholewa.blinds.state;

import java.util.Arrays;
import java.util.Optional;

/** The {@code event} of a Gen1 action call, exactly as written in the {@code roller_*_url} of the device. */
public enum Gen1Action {
    OPEN("open"), CLOSE("close"), STOP("stop");

    private final String value;

    Gen1Action(final String value) {
        this.value = value;
    }

    public static Optional<Gen1Action> ofParameter(final String parameter) {
        return Arrays.stream(values()).filter(action -> action.value.equals(parameter)).findFirst();
    }
}
