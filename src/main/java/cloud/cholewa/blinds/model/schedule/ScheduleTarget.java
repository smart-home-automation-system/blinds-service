package cloud.cholewa.blinds.model.schedule;

import cloud.cholewa.home.model.RoomName;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What a schedule moves: every blind of a {@code room} (the value of the registry, {@code living room})
 * or one blind, by its {@code deviceId} in the device registry - one of the two.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScheduleTarget(RoomName room, Long deviceId) {

    public static ScheduleTarget room(final RoomName room) {
        return new ScheduleTarget(room, null);
    }

    public static ScheduleTarget blind(final long deviceId) {
        return new ScheduleTarget(null, deviceId);
    }
}
