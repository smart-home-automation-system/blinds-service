package cloud.cholewa.blinds.api;

import cloud.cholewa.blinds.model.BlindView;
import cloud.cholewa.blinds.model.RoomBlinds;
import cloud.cholewa.blinds.service.BlindsQueryService;
import cloud.cholewa.blinds.state.Gen1Action;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.util.List;

//The state of the blinds, and the Gen1 actions that report it. The actions are a GET because that
//is what a Gen1 device calls (roller_open_url, roller_close_url, roller_stop_url); the gateway may
//repeat a GET whose connection broke, which is harmless - the same action twice is the same state
@RestController
@RequiredArgsConstructor
public class BlindsReadController {

    private final BlindsQueryService blindsQueryService;

    @GetMapping
    Mono<List<RoomBlinds>> all() {
        return blindsQueryService.all();
    }

    @GetMapping("/rooms/{room}")
    Mono<RoomBlinds> room(@PathVariable final String room) {
        return blindsQueryService.inRoom(Rooms.exactly(room));
    }

    @GetMapping("/{id}")
    Mono<BlindView> one(@PathVariable final long id) {
        return blindsQueryService.one(id);
    }

    @GetMapping("/events/gen1")
    Mono<ResponseEntity<Void>> gen1Action(@RequestParam final long device, @RequestParam final String event) {
        final Gen1Action action = Gen1Action.ofParameter(event)
            .orElseThrow(() -> new ServerWebInputException("event must be open, close or stop"));

        return blindsQueryService.gen1Action(device, action)
            .then(Mono.fromSupplier(() -> ResponseEntity.noContent().build()));
    }
}
