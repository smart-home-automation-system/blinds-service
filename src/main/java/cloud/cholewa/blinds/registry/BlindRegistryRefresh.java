package cloud.cholewa.blinds.registry;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

//The registry read at the start and every five minutes. Spring subscribes again to the same Mono
//for every run, which is why the read itself is deferred in BlindRegistry.refresh(). fixedDelay:
//the next read waits for the last one to end. Scheduling is off under the test profile.
//Bounded (HAS-301): one call of database-service with a response timeout of seconds - a read that
//takes a minute is hung, and ends there; the last list stays
@Component
@RequiredArgsConstructor
public class BlindRegistryRefresh {

    static final Duration LIMIT = Duration.ofMinutes(1);

    private final BlindRegistry blindRegistry;

    @Scheduled(initialDelayString = "PT0S", fixedDelayString = "PT5M")
    Mono<Void> refresh() {
        return ScheduledPass.bounded("blinds registry refresh", LIMIT, blindRegistry::refresh);
    }
}
