package cloud.cholewa.blinds.registry;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

//The registry read at the start and every five minutes. Spring subscribes again to the same Mono
//for every run, which is why the read itself is deferred in BlindRegistry.refresh(). fixedDelay:
//the next read waits for the last one to end. Scheduling is off under the test profile
@Component
@RequiredArgsConstructor
public class BlindRegistryRefresh {

    private final BlindRegistry blindRegistry;

    @Scheduled(initialDelayString = "PT0S", fixedDelayString = "PT5M")
    Mono<Void> refresh() {
        return blindRegistry.refresh();
    }
}
