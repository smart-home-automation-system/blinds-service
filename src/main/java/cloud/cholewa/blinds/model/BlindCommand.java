package cloud.cholewa.blinds.model;

import java.util.Arrays;
import java.util.Optional;

/** What a blind is told to do, by the value of the {@code command} parameter, exactly as written. */
public enum BlindCommand {
    OPEN("open"), CLOSE("close"), STOP("stop");

    private final String value;

    BlindCommand(final String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<BlindCommand> ofParameter(final String parameter) {
        return Arrays.stream(values()).filter(command -> command.value.equals(parameter)).findFirst();
    }
}
