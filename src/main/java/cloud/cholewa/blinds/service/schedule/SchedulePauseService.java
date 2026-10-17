package cloud.cholewa.blinds.service.schedule;

import cloud.cholewa.blinds.database.model.SchedulePauseEntity;
import cloud.cholewa.blinds.database.repository.SchedulePauseRepository;
import cloud.cholewa.blinds.registry.BlindRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

//The pause of the schedules of one blind (HAS-232): every schedule of the blind is held back until
//the next run it prevents, which the engine stores as PAUSED and with which it ends the pause
//(ScheduleEngine). A blind the registry does not know is a 404; pausing a paused blind keeps its
//start, resuming one that is not paused changes nothing - both are 204 for the dashboard
@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulePauseService {

    private final BlindRegistry blindRegistry;
    private final SchedulePauseRepository pauseRepository;
    private final Clock clock;

    //the pauses as last read - every minute by the engine, and by every read of the blinds
    private final AtomicReference<Map<Long, LocalDateTime>> lastRead = new AtomicReference<>(Map.of());

    public Mono<Void> pause(final long id) {
        return blindRegistry.find(id)
            .flatMap(blind -> pauseRepository.pause(blind.id(), LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS))
                .doOnNext(inserted -> {
                    if (inserted > 0) {
                        log.info("The schedules of blind {} ({}) are paused until the next run", blind.id(), blind.name());
                    }
                }))
            .then();
    }

    public Mono<Void> resume(final long id) {
        return blindRegistry.find(id)
            .flatMap(blind -> pauseRepository.findById(blind.id())
                .flatMap(pause -> pauseRepository.delete(pause)
                    .doOnSuccess(done -> log.info("The schedules of blind {} ({}) are resumed", blind.id(), blind.name()))));
    }

    //the engine ends a pause with the run it prevented - by the id alone: a blind gone from the
    //registry is no reason to keep its row
    public Mono<Void> end(final long id) {
        return pauseRepository.deleteById(id);
    }

    //since when each paused blind is paused - strict: the engine fires nothing without it
    public Mono<Map<Long, LocalDateTime>> paused() {
        return pauseRepository.findAll()
            .collectMap(SchedulePauseEntity::deviceId, SchedulePauseEntity::pausedAt)
            .doOnNext(lastRead::set);
    }

    //for the reads of the blinds, which were served from memory before the pauses and must not fail
    //for them: a database away answers the pauses as last read - at most a minute old while the engine
    //runs - with a WARN
    public Mono<Map<Long, LocalDateTime>> pausedAsLastKnown() {
        return paused().onErrorResume(e -> {
            log.warn("The pauses of the schedules could not be read, the last ones read are answered: {}", e.toString());
            return Mono.just(lastRead.get());
        });
    }
}
