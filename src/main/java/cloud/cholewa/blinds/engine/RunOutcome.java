package cloud.cholewa.blinds.engine;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What came of the command a schedule sent to one blind: {@code moved} - the device took it;
 * {@code failed} - it did not, or did not answer, and may have moved all the same;
 * {@code unsupported} - a position for a blind that cannot be sent to one. Stored by the name of
 * the constant (V3) - renaming one needs a migration of the rows.
 */
public enum RunOutcome {
    MOVED("moved"), FAILED("failed"), UNSUPPORTED("unsupported");

    private final String value;

    RunOutcome(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
