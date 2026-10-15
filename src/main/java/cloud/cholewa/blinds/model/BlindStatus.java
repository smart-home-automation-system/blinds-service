package cloud.cholewa.blinds.model;

import java.time.LocalDateTime;

/**
 * The state of a blind as the service knows it now, kept in memory. Every field may be
 * {@code null}, which is "not known": no state before the first report, no position from a blind
 * without one, no reachability before anything was heard from the device or after a restart.
 *
 * @param movedAt   when the state began - the start of the movement or of the rest
 * @param source    who moved it: {@code dashboard}, {@code schedule}, {@code protection} for a
 *                  command of this service, the word of a Shelly device, otherwise {@code external}
 */
public record BlindStatus(
    BlindState state,
    Integer position,
    Direction lastDirection,
    LocalDateTime movedAt,
    String source,
    Boolean reachable
) {

    public static BlindStatus unknown() {
        return new BlindStatus(null, null, null, null, null, null);
    }

    public BlindStatus withReachable(final Boolean reachable) {
        return new BlindStatus(state, position, lastDirection, movedAt, source, reachable);
    }
}
