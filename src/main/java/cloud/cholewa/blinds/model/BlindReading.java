package cloud.cholewa.blinds.model;

/**
 * What one report of a device says about a blind - an MQTT status, an answer to a poll, a Gen1
 * action, an Eaton status. A {@code null} is what the report did not say, never off or zero.
 *
 * @param state         never {@code null}: a report without a state the service knows is no reading
 * @param position      0 (closed) to 100 (open), only from a blind that knows its position
 * @param deviceSource  what the device named as the cause of the change ({@code SHC}, {@code button})
 */
public record BlindReading(BlindState state, Integer position, Direction lastDirection, String deviceSource) {
}
