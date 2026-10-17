package cloud.cholewa.blinds.model;

import cloud.cholewa.home.model.BlindsPlacement;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;

import java.util.List;

/**
 * A blind as the service knows it, from the device registry. Kept in memory only and never
 * serialized - an answer is mapped from it by hand.<br>
 * {@code positioning} - whether it can be sent to a position; {@code false} when the registry
 * does not say.<br>
 * {@code openingSensors} - the registry ids of the opening sensors on its window (HAS-290), none
 * for a window without a sensor.
 */
public record Blind(
    long id,
    String name,
    RoomName room,
    SmartVendor vendor,
    BlindBinding binding,
    BlindsPlacement placement,
    boolean positioning,
    List<Long> openingSensors
) {

    public Blind {
        openingSensors = openingSensors == null ? List.of() : List.copyOf(openingSensors);
    }

    //a blind with no sensor on its window
    public Blind(final long id, final String name, final RoomName room, final SmartVendor vendor, final BlindBinding binding,
                 final BlindsPlacement placement, final boolean positioning) {
        this(id, name, room, vendor, binding, placement, positioning, List.of());
    }
}
