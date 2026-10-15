package cloud.cholewa.blinds.model;

import cloud.cholewa.home.model.BlindsPlacement;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;

/**
 * A blind as the service knows it, from the device registry. Kept in memory only and never
 * serialized - an answer is mapped from it by hand.<br>
 * {@code positioning} - whether it can be sent to a position; {@code false} when the registry
 * does not say.
 */
public record Blind(
    long id,
    String name,
    RoomName room,
    SmartVendor vendor,
    BlindBinding binding,
    BlindsPlacement placement,
    boolean positioning
) {
}
