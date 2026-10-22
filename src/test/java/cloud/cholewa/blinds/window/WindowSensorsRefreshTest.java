package cloud.cholewa.blinds.window;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//HAS-301: a read of the window sensors that hangs ends at its bound, below the five minutes between reads
class WindowSensorsRefreshTest {

    private final WindowSensors windowSensors = mock(WindowSensors.class);
    private final WindowSensorsRefresh scheduled = new WindowSensorsRefresh(windowSensors);

    @Test
    void should_end_a_read_that_never_completes_at_its_bound() {
        when(windowSensors.refresh()).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(scheduled::refresh)
            .expectSubscription()
            .expectNoEvent(WindowSensorsRefresh.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }
}
