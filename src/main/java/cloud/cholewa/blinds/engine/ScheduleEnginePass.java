package cloud.cholewa.blinds.engine;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

//A pass of the schedule engine every minute, the first one a minute after the start - the registry
//of the blinds is read by then. fixedDelay: a pass waits for the last one to end. Spring subscribes
//again to the same Mono every minute, which is why everything in ScheduleEngine.pass() is deferred.
//Scheduling is off under the test profile.
//Bounded (HAS-301): the reading of the inputs has its own 50 s, but the commands are never cut off -
//one cut off halfway may have moved a blind with no run stored - so the bound of the whole pass is
//well above a pass that fires every blind at once (14 blinds one after the other, each at most the
//connect and response timeouts of its client, about 3 minutes) and only ends a pass that hangs - on
//2026-10-22 one waited for good on a dead database connection. A blind is remembered in memory
//before its command is sent, so a pass ended at its bound never moves it twice
@Component
@RequiredArgsConstructor
public class ScheduleEnginePass {

    static final Duration LIMIT = Duration.ofMinutes(5);

    private final ScheduleEngine scheduleEngine;

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    Mono<Void> pass() {
        return ScheduledPass.bounded("blinds schedule engine", LIMIT, scheduleEngine::pass);
    }
}
