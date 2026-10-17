package cloud.cholewa.blinds.api;

import cloud.cholewa.blinds.engine.BlindSchedules;
import cloud.cholewa.blinds.model.schedule.BlindScheduleToday;
import cloud.cholewa.blinds.service.schedule.SchedulePauseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

//The schedules of one blind (HAS-232): which apply to it today and what came of them, and the pause
//of all of them until the next run it prevents. PUT and DELETE are idempotent - a second pause keeps
//the first, a resume of a blind that is not paused changes nothing; an unknown blind is a 404
@RestController
@RequiredArgsConstructor
public class BlindPauseController {

    private final SchedulePauseService schedulePauseService;
    private final BlindSchedules blindSchedules;

    @GetMapping("/{id}/schedules")
    Mono<List<BlindScheduleToday>> schedules(@PathVariable final long id) {
        return blindSchedules.of(id);
    }

    @PutMapping("/{id}/schedule-pause")
    Mono<ResponseEntity<Void>> pause(@PathVariable final long id) {
        return schedulePauseService.pause(id).then(Mono.fromSupplier(() -> ResponseEntity.noContent().build()));
    }

    @DeleteMapping("/{id}/schedule-pause")
    Mono<ResponseEntity<Void>> resume(@PathVariable final long id) {
        return schedulePauseService.resume(id).then(Mono.fromSupplier(() -> ResponseEntity.noContent().build()));
    }
}
