package cloud.cholewa.blinds.state;

import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.BlindReading;
import cloud.cholewa.blinds.model.BlindState;
import cloud.cholewa.blinds.registry.BlindRegistry;
import cloud.cholewa.home.model.BlindsPlacement;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.shelly.http.ShellyCallException;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.model.CoverStatus;
import cloud.cholewa.shelly.model.Gen1DeviceStatus;
import cloud.cholewa.shelly.model.RollerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlindStatePollTest {

    private static final Blind KITCHEN = new Blind(17, "Kitchen window", RoomName.KITCHEN, SmartVendor.EATON,
        new BlindBinding.Eaton(9), BlindsPlacement.EXTERNAL, false);
    private static final Blind DOOR = new Blind(53, "Office door", RoomName.OFFICE, SmartVendor.SHELLY,
        new BlindBinding.ShellyGen2("10.0.0.50", 0, "shellyplus2pm-a1"), BlindsPlacement.EXTERNAL, true);
    private static final Blind WINDOW = new Blind(52, "Office window", RoomName.OFFICE, SmartVendor.SHELLY,
        new BlindBinding.ShellyGen1("10.0.0.59", 0), BlindsPlacement.EXTERNAL, true);
    private static final Blind BEDROOM = new Blind(60, "Bedroom window", RoomName.BEDROOM, SmartVendor.SHELLY,
        new BlindBinding.ShellyGen1("10.0.0.88", 0), BlindsPlacement.EXTERNAL, true);

    private final BlindRegistry registry = mock(BlindRegistry.class);
    private final BlindStateTracker tracker = mock(BlindStateTracker.class);
    private final ShellyGen1Client gen1 = mock(ShellyGen1Client.class);
    private final ShellyRpcClient rpc = mock(ShellyRpcClient.class);
    private final BlindStatePoll poll = new BlindStatePoll(registry, tracker, gen1, rpc);

    @BeforeEach
    void setUp() {
        when(registry.current()).thenReturn(List.of(KITCHEN, DOOR, WINDOW, BEDROOM));
        when(tracker.observe(any(), any())).thenReturn(Mono.empty());
        when(tracker.reachability(any(), anyBoolean(), any())).thenReturn(Mono.empty());
    }

    @Test
    void should_read_every_gen1_roller_and_nothing_else() {
        when(gen1.status("10.0.0.59")).thenReturn(Mono.just(gen1Status(roller("stop", 100))));
        when(gen1.status("10.0.0.88")).thenReturn(Mono.just(gen1Status(roller("close", 40))));

        StepVerifier.create(poll.pollGen1()).verifyComplete();

        verify(tracker).observe(WINDOW, new BlindReading(BlindState.OPEN, 100, null, null));
        verify(tracker).observe(BEDROOM, new BlindReading(BlindState.CLOSING, 40, null, null));
        verify(rpc, never()).coverStatus(anyString(), anyInt());
    }

    //one device that does not answer neither stops the pass nor fails it
    @Test
    void should_mark_a_roller_that_does_not_answer_unreachable_and_read_the_others() {
        when(gen1.status("10.0.0.59")).thenReturn(Mono.error(new ShellyCallException("GET /status of 10.0.0.59 timed out")));
        when(gen1.status("10.0.0.88")).thenReturn(Mono.just(gen1Status(roller("stop", 0))));

        StepVerifier.create(poll.pollGen1()).verifyComplete();

        verify(tracker).reachability(eq(WINDOW), eq(false), any());
        verify(tracker).observe(BEDROOM, new BlindReading(BlindState.CLOSED, 0, null, null));
    }

    @Test
    void should_take_a_status_without_the_roller_as_a_failed_read() {
        when(gen1.status(anyString())).thenReturn(Mono.just(gen1Status()));

        StepVerifier.create(poll.pollGen1()).verifyComplete();

        verify(tracker).reachability(eq(WINDOW), eq(false), any());
        verify(tracker, never()).observe(any(), any());
    }

    //a store that failed is not a device that did not answer
    @Test
    void should_not_take_a_failed_store_for_an_unreachable_blind() {
        when(gen1.status(anyString())).thenReturn(Mono.just(gen1Status(roller("stop", 0))));
        when(tracker.observe(any(), any())).thenReturn(Mono.error(new IllegalStateException("no connection")));

        StepVerifier.create(poll.pollGen1()).verifyComplete();

        verify(tracker, never()).reachability(any(), eq(false), any());
    }

    @Test
    void should_reconcile_every_gen2_cover() {
        final CoverStatus cover = new CoverStatus();
        cover.setId(0);
        cover.setState("closed");
        cover.setPosControl(true);
        cover.setCurrentPos(0);
        when(rpc.coverStatus("10.0.0.50", 0)).thenReturn(Mono.just(cover));

        StepVerifier.create(poll.reconcileGen2()).verifyComplete();

        verify(tracker).observe(DOOR, new BlindReading(BlindState.CLOSED, 0, null, null));
        verify(gen1, never()).status(anyString());
    }

    //Spring subscribes again to the same Mono for every run: each run reads the registry anew
    @Test
    void should_build_every_pass_anew() {
        final AtomicInteger reads = new AtomicInteger();
        when(registry.current()).thenAnswer(invocation -> {
            reads.incrementAndGet();
            return List.of();
        });
        final Mono<Void> pass = poll.pollGen1();

        StepVerifier.create(pass).verifyComplete();
        StepVerifier.create(pass).verifyComplete();

        assertThat(reads).hasValue(2);
    }

    private static Gen1DeviceStatus gen1Status(final RollerStatus... rollers) {
        final Gen1DeviceStatus status = new Gen1DeviceStatus();
        status.setRollers(List.of(rollers));
        return status;
    }

    private static RollerStatus roller(final String state, final int position) {
        final RollerStatus roller = new RollerStatus();
        roller.setState(state);
        roller.setPositioning(true);
        roller.setCurrentPos(position);
        return roller;
    }
}
