package cloud.cholewa.blinds.window;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

//The window sensors and their gateways read at the start and every five minutes. Spring subscribes
//again to the same Mono for every run, which is why the read itself is deferred in
//WindowSensors.refresh(). Scheduling is off under the test profile.
//Bounded (HAS-301): the registry and every BTHome gateway page by page, each call with its own
//timeout - two minutes is a pass that hangs; the state read so far stays
@Component
@RequiredArgsConstructor
public class WindowSensorsRefresh {

    static final Duration LIMIT = Duration.ofMinutes(2);

    private final WindowSensors windowSensors;

    @Scheduled(initialDelayString = "PT0S", fixedDelayString = "PT5M")
    Mono<Void> refresh() {
        return ScheduledPass.bounded("blinds window sensors refresh", LIMIT, windowSensors::refresh);
    }
}
