package cloud.cholewa.blinds.model;

import java.util.List;

/** The blinds of one room, in the order of the registry. */
public record RoomBlinds(String room, List<BlindView> blinds) {
}
