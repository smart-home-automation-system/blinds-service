package cloud.cholewa.blinds.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The answer of a command for every blind of a room: which were moved and which were skipped,
 * each skipped one with the code of why. {@code moved} and {@code failed} are always there,
 * empty or not.
 */
public record RoomCommandReply(String room, String command, List<BlindOutcome> moved, List<BlindOutcome> failed) {

    /** One blind of the room; {@code code} only for one that was skipped. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BlindOutcome(long id, String name, String code) {
    }
}
