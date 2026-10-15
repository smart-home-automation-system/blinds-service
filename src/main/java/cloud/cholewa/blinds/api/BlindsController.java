package cloud.cholewa.blinds.api;

import cloud.cholewa.blinds.model.BlindCommand;
import cloud.cholewa.blinds.model.CommandSource;
import cloud.cholewa.blinds.model.RoomCommandReply;
import cloud.cholewa.blinds.service.BlindsCommandService;
import cloud.cholewa.home.model.RoomName;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

//The commands for the blinds. 202, not 200: the answer says the device took the command, never that
//the blind got there. The values are taken exactly as written - a typo moves nothing.
//No @Validated on the class - WebFlux validates the constrained parameters itself, and the
//annotation would switch that off. The messages are written on the constraints, so the answer reads
//the same on every machine
@RestController
@RequiredArgsConstructor
public class BlindsController {

    private static final String POSITION_RANGE = "value must be between 0 and 100";

    private final BlindsCommandService blindsCommandService;

    @PostMapping("/{id}")
    Mono<ResponseEntity<Void>> move(
        @PathVariable final long id,
        @RequestParam final String command,
        @RequestParam(defaultValue = "dashboard") final String source
    ) {
        return blindsCommandService.move(id, command(command), source(source))
            .then(Mono.fromSupplier(() -> ResponseEntity.accepted().build()));
    }

    @PostMapping("/{id}/position")
    Mono<ResponseEntity<Void>> moveTo(
        @PathVariable final long id,
        @RequestParam @Min(value = 0, message = POSITION_RANGE) @Max(value = 100, message = POSITION_RANGE)
        final int value,
        @RequestParam(defaultValue = "dashboard") final String source
    ) {
        return blindsCommandService.moveTo(id, value, source(source))
            .then(Mono.fromSupplier(() -> ResponseEntity.accepted().build()));
    }

    @PostMapping("/rooms/{room}")
    Mono<ResponseEntity<RoomCommandReply>> moveRoom(
        @PathVariable final String room,
        @RequestParam final String command,
        @RequestParam(defaultValue = "dashboard") final String source
    ) {
        return blindsCommandService.moveRoom(room(room), command(command), source(source))
            .map(reply -> ResponseEntity.accepted().body(reply));
    }

    private static BlindCommand command(final String command) {
        return BlindCommand.ofParameter(command)
            .orElseThrow(() -> new ServerWebInputException("command must be open, close or stop"));
    }

    private static CommandSource source(final String source) {
        return CommandSource.ofParameter(source)
            .orElseThrow(() -> new ServerWebInputException("source must be dashboard, schedule or protection"));
    }

    //the value of the room as the registry writes it, "living room" included
    private static RoomName room(final String room) {
        try {
            return RoomName.fromValue(room);
        } catch (IllegalArgumentException e) {
            throw new ServerWebInputException("room is not a room of the house");
        }
    }
}
