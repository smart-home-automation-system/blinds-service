package cloud.cholewa.blinds.api;

import cloud.cholewa.blinds.model.schedule.Schedule;
import cloud.cholewa.blinds.service.schedule.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

//The schedules of the blinds, the shape of the device registry's endpoints: a write answers the
//schedule as stored, PUT replaces it whole, enable and disable switch it without sending the rest.
//No @Valid: every rule of a body is ScheduleRules', which answers INVALID_SCHEDULE with the reason.
//"/schedules" is a literal, so it is never taken for the {id} of a blind
@RestController
@RequiredArgsConstructor
@RequestMapping("/schedules")
public class BlindsScheduleController {

    private final ScheduleService scheduleService;

    @GetMapping
    Mono<List<Schedule>> all() {
        return scheduleService.all();
    }

    @GetMapping("/{id}")
    Mono<Schedule> one(@PathVariable final long id) {
        return scheduleService.get(id);
    }

    @PostMapping
    Mono<ResponseEntity<Schedule>> create(@RequestBody final Schedule schedule) {
        return scheduleService.create(schedule)
            .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created));
    }

    @PutMapping("/{id}")
    Mono<Schedule> replace(@PathVariable final long id, @RequestBody final Schedule schedule) {
        return scheduleService.replace(id, schedule);
    }

    @PostMapping("/{id}/enable")
    Mono<Schedule> enable(@PathVariable final long id) {
        return scheduleService.enable(id);
    }

    @PostMapping("/{id}/disable")
    Mono<Schedule> disable(@PathVariable final long id) {
        return scheduleService.disable(id);
    }

    @DeleteMapping("/{id}")
    Mono<ResponseEntity<Void>> remove(@PathVariable final long id) {
        return scheduleService.remove(id)
            .then(Mono.fromSupplier(() -> ResponseEntity.noContent().build()));
    }
}
