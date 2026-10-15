package cloud.cholewa.blinds.api;

import cloud.cholewa.home.model.RoomName;
import org.springframework.web.server.ServerWebInputException;

import java.util.Arrays;

final class Rooms {

    private Rooms() {
    }

    //the value of the room exactly as the registry writes it, "living room" included - fromValue of
    //the SDK ignores the case, a request here does not
    static RoomName exactly(final String room) {
        return Arrays.stream(RoomName.values())
            .filter(name -> name.getValue().equals(room))
            .findFirst()
            .orElseThrow(() -> new ServerWebInputException("room is not a room of the house"));
    }
}
