package cloud.cholewa.blinds.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Who asked for a move, by the value of the {@code source} parameter: a person on the dashboard,
 * a schedule, or the wind and frost protection. Written to the log of every command, and the
 * {@code source} of the state of a blind the command moved.
 */
public enum CommandSource {
    DASHBOARD("dashboard"), SCHEDULE("schedule"), PROTECTION("protection");

    private final String value;

    CommandSource(final String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<CommandSource> ofParameter(final String parameter) {
        return Arrays.stream(values()).filter(source -> source.value.equals(parameter)).findFirst();
    }
}
