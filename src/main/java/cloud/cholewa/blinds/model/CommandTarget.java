package cloud.cholewa.blinds.model;

/**
 * What a command asks of a blind - an open or a close (its direction), a stop, or a position - so
 * that a report of the device can be told to answer it (HAS-250).
 *
 * @param direction the direction of an open or a close; {@code null} for a stop or a position
 * @param stop      whether the command stops the blind
 * @param position  the position a blind is sent to, 0 (closed) to 100 (open); {@code null} otherwise
 */
public record CommandTarget(Direction direction, boolean stop, Integer position) {

    public static CommandTarget move(final Direction direction) {
        return new CommandTarget(direction, false, null);
    }

    public static CommandTarget halt() {
        return new CommandTarget(null, true, null);
    }

    public static CommandTarget position(final int position) {
        return new CommandTarget(null, false, position);
    }

    /**
     * Whether a blind in this state answers the command: moving its way or at its end for an open
     * or a close, at rest for a stop, moving or at the position for a position. A state not known
     * answers nothing.
     */
    public boolean answeredBy(final BlindState state, final Integer reportedPosition) {
        if (state == null) {
            return false;
        }
        if (stop) {
            return !state.moving();
        }
        if (direction != null) {
            return direction == Direction.OPEN
                ? state == BlindState.OPENING || state == BlindState.OPEN
                : state == BlindState.CLOSING || state == BlindState.CLOSED;
        }
        return state.moving()
            || position.equals(reportedPosition)
            || position == 100 && state == BlindState.OPEN
            || position == 0 && state == BlindState.CLOSED;
    }
}
