package cloud.cholewa.blinds.engine;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//HAS-301: on 2026-10-22 a pass waited for good on a dead database connection and held the scheduler of
//the service for 1.5 hours. A pass of the engine ends at its bound - and not before: its commands must
//not be cut off halfway
class ScheduleEnginePassTest {

    private final ScheduleEngine engine = mock(ScheduleEngine.class);
    private final ScheduleEnginePass scheduled = new ScheduleEnginePass(engine);

    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        final AtomicBoolean cancelled = new AtomicBoolean();
        when(engine.pass()).thenReturn(Mono.<Void>never().doOnCancel(() -> cancelled.set(true)));

        StepVerifier.withVirtualTime(scheduled::pass)
            .expectSubscription()
            .expectNoEvent(ScheduleEnginePass.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();

        assertThat(cancelled).isTrue();
    }

    //a pass that fires every blind at once takes about three minutes at the worst - well inside
    @Test
    void should_leave_room_for_the_commands_of_a_whole_pass() {
        assertThat(Duration.ofMinutes(5)).isEqualTo(ScheduleEnginePass.LIMIT);
    }
}
