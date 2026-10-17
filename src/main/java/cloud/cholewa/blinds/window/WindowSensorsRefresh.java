package cloud.cholewa.blinds.window;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

//The window sensors and their gateways read at the start and every five minutes. Spring subscribes
//again to the same Mono for every run, which is why the read itself is deferred in
//WindowSensors.refresh(). Scheduling is off under the test profile
@Component
@RequiredArgsConstructor
public class WindowSensorsRefresh {

    private final WindowSensors windowSensors;

    @Scheduled(initialDelayString = "PT0S", fixedDelayString = "PT5M")
    Mono<Void> refresh() {
        return windowSensors.refresh();
    }
}
