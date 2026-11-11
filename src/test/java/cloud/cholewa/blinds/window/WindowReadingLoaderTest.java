package cloud.cholewa.blinds.window;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//HAS-420: the stored window readings read back before the device listener and the read of the gateways, never fatal
class WindowReadingLoaderTest {

    private final WindowSensors windowSensors = mock(WindowSensors.class);
    private final WindowReadingLoader sut = new WindowReadingLoader(windowSensors);

    @Test
    void should_read_the_stored_readings_back_before_the_listener_starts() {
        final AtomicBoolean restored = new AtomicBoolean();
        when(windowSensors.restore()).thenReturn(Mono.fromRunnable(() -> restored.set(true)));

        sut.start();

        assertThat(restored).isTrue();
        assertThat(sut.isRunning()).isTrue();
        assertThat(sut.getPhase()).isLessThan(new RabbitListenerEndpointRegistry().getPhase());
        sut.stop();
        assertThat(sut.isRunning()).isFalse();
    }

    //they are only what a gateway value is compared with: a failure never stops the start
    @Test
    void should_start_when_the_stored_readings_fail() {
        when(windowSensors.restore()).thenReturn(Mono.error(new IllegalStateException("database down")));

        sut.start();

        assertThat(sut.isRunning()).isTrue();
    }
}
