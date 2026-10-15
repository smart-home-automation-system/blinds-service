package cloud.cholewa.blinds.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * A blind in the answer of a read: what the registry says of it and the state the devices
 * reported. A field the service does not know is left out - never answered as off, closed or
 * zero. {@code protection} comes with HAS-249.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BlindView(
    long id,
    String name,
    String room,
    String vendor,
    String placement,
    boolean positioning,
    String state,
    Integer position,
    String lastDirection,
    LocalDateTime movedAt,
    String source,
    Boolean reachable
) {

    public static BlindView of(final Blind blind, final BlindStatus status) {
        return new BlindView(
            blind.id(),
            blind.name(),
            blind.room().getValue(),
            blind.vendor().getValue(),
            blind.placement() == null ? null : blind.placement().getValue(),
            blind.positioning(),
            status.state() == null ? null : status.state().value(),
            status.position(),
            status.lastDirection() == null ? null : status.lastDirection().value(),
            status.movedAt(),
            status.source(),
            status.reachable()
        );
    }
}
