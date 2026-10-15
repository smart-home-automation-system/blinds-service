package cloud.cholewa.blinds.model;

/**
 * Where a blind is, or what it does, as the devices report it. {@code open} and {@code closed}
 * only for a blind that said it reached the end - an Eaton actuator never does, it reports a
 * movement and its stop.
 */
public enum BlindState {
    OPEN("open"), CLOSED("closed"), OPENING("opening"), CLOSING("closing"), STOPPED("stopped");

    private final String value;

    BlindState(final String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean moving() {
        return this == OPENING || this == CLOSING;
    }

    /** The direction of a movement; nothing for a blind at rest. */
    public Direction direction() {
        return switch (this) {
            case OPENING -> Direction.OPEN;
            case CLOSING -> Direction.CLOSE;
            default -> null;
        };
    }
}
