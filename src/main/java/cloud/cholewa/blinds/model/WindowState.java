package cloud.cholewa.blinds.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The window of one blind (HAS-290), judged by the opening sensors the registry links to that
 * blind - never by the room: {@code closed} - every sensor reports closed; {@code open} - one
 * reports open; {@code no sensor} - the window has none, and counts as closed; {@code unknown} -
 * a sensor never reported since the start, or its gateway has been silent too long. The values are
 * a contract the dashboard copies.
 */
public enum WindowState {
    CLOSED("closed"),
    OPEN("open"),
    NO_SENSOR("no sensor"),
    UNKNOWN("unknown");

    private final String value;

    WindowState(final String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    //what a "window closed" condition reads: a window without a sensor is a closed one
    public Boolean closed() {
        return switch (this) {
            case CLOSED, NO_SENSOR -> true;
            case OPEN -> false;
            case UNKNOWN -> null;
        };
    }
}
