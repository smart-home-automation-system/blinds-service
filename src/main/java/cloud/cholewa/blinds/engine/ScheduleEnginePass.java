package cloud.cholewa.blinds.engine;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

//A pass of the schedule engine every minute, the first one a minute after the start - the registry
//of the blinds is read by then. fixedDelay: a pass waits for the last one to end. Spring subscribes
//again to the same Mono every minute, which is why everything in ScheduleEngine.pass() is deferred.
//Scheduling is off under the test profile
@Component
@RequiredArgsConstructor
public class ScheduleEnginePass {

    private final ScheduleEngine scheduleEngine;

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    Mono<Void> pass() {
        return scheduleEngine.pass();
    }
}
