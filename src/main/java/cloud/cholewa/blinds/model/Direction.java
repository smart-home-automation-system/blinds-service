package cloud.cholewa.blinds.model;

/** The way a blind moved last, in the words of Shelly: {@code open} or {@code close}. */
public enum Direction {
    OPEN("open"), CLOSE("close");

    private final String value;

    Direction(final String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
