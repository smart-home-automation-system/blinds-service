package cloud.cholewa.blinds.service;

import cloud.cholewa.blinds.error.BlindsError;
import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.BlindReading;
import cloud.cholewa.blinds.model.BlindState;
import cloud.cholewa.blinds.model.BlindStatus;
import cloud.cholewa.blinds.model.BlindView;
import cloud.cholewa.blinds.model.Direction;
import cloud.cholewa.blinds.model.RoomBlinds;
import cloud.cholewa.blinds.model.WindowState;
import cloud.cholewa.blinds.registry.BlindRegistry;
import cloud.cholewa.blinds.state.BlindStateTracker;
import cloud.cholewa.blinds.state.Gen1Action;
import cloud.cholewa.blinds.service.schedule.SchedulePauseService;
import cloud.cholewa.blinds.window.WindowSensors;
import cloud.cholewa.home.model.BlindsPlacement;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlindsQueryServiceTest {

    private static final LocalDateTime MOVED = LocalDateTime.of(2026, 10, 15, 18, 0);

    private static final Blind OFFICE_WINDOW = blind(52, RoomName.OFFICE, new BlindBinding.ShellyGen1("10.0.0.59", 0));
    private static final Blind KITCHEN = blind(17, RoomName.KITCHEN, new BlindBinding.Eaton(9));
    private static final Blind OFFICE_DOOR = blind(53, RoomName.OFFICE, new BlindBinding.ShellyGen2("10.0.0.50", 0, "a1"));

    //HAS-232: the schedules of the office door are paused since then
    private static final LocalDateTime PAUSED_AT = LocalDateTime.of(2026, 10, 17, 20, 15);

    private final BlindRegistry registry = mock(BlindRegistry.class);
    private final BlindStateTracker tracker = mock(BlindStateTracker.class);
    private final WindowSensors windowSensors = mock(WindowSensors.class);
    private final SchedulePauseService pauseService = mock(SchedulePauseService.class);
    private final BlindsQueryService service = new BlindsQueryService(registry, tracker, windowSensors, pauseService);

    @BeforeEach
    void setUp() {
        when(tracker.status(anyLong())).thenReturn(Mono.just(BlindStatus.unknown()));
        when(tracker.observe(any(), any())).thenReturn(Mono.empty());
        when(windowSensors.stateOf(any())).thenReturn(WindowState.NO_SENSOR);
        when(pauseService.pausedAsLastKnown()).thenReturn(Mono.just(Map.of(53L, PAUSED_AT)));
    }

    //the rooms in the order of the SDK, the blinds of a room in the order of the registry
    @Test
    void should_group_the_blinds_by_room() {
        when(registry.all()).thenReturn(Mono.just(List.of(OFFICE_WINDOW, KITCHEN, OFFICE_DOOR)));
        when(tracker.status(53)).thenReturn(Mono.just(
            new BlindStatus(BlindState.CLOSED, 0, Direction.CLOSE, MOVED, "SHC", true)));

        service.all().as(StepVerifier::create)
            .assertNext(rooms -> {
                assertThat(rooms).extracting(RoomBlinds::room).containsExactly("office", "kitchen");
                assertThat(rooms.get(0).blinds()).extracting(BlindView::id).containsExactly(52L, 53L);
                assertThat(rooms.get(0).blinds().get(1)).isEqualTo(new BlindView(53, "Blind 53", "office", "shelly",
                    "external", true, "closed", 0, "close", MOVED, "SHC", true, "no sensor", PAUSED_AT));
                assertThat(rooms.get(0).blinds().get(0).schedulePausedSince()).isNull();
            })
            .verifyComplete();
    }

    @Test
    void should_store_an_action_of_a_gen1_roller() {
        when(registry.find(52)).thenReturn(Mono.just(OFFICE_WINDOW));

        service.gen1Action(52, Gen1Action.CLOSE).as(StepVerifier::create).verifyComplete();

        verify(tracker).observe(OFFICE_WINDOW, new BlindReading(BlindState.CLOSED, 0, Direction.CLOSE, null));
    }

    //a device configured with the id of a blind that is not a Gen1 roller
    @Test
    void should_refuse_an_action_of_a_blind_that_is_not_a_gen1_roller() {
        when(registry.find(53)).thenReturn(Mono.just(OFFICE_DOOR));

        service.gen1Action(53, Gen1Action.OPEN).as(StepVerifier::create)
            .expectErrorSatisfies(e -> assertThat(((BlindsException) e).getError()).isEqualTo(BlindsError.NOT_FOUND_BLINDS))
            .verify();

        verify(tracker, never()).observe(any(), any());
    }

    //the device does not call again: a store that failed is logged, the state is known
    @Test
    void should_accept_an_action_whose_store_failed() {
        when(registry.find(52)).thenReturn(Mono.just(OFFICE_WINDOW));
        when(tracker.observe(any(), any())).thenReturn(Mono.error(new IllegalStateException("no connection")));

        service.gen1Action(52, Gen1Action.STOP).as(StepVerifier::create).verifyComplete();
    }

    private static Blind blind(final long id, final RoomName room, final BlindBinding binding) {
        return new Blind(id, "Blind " + id, room, binding instanceof BlindBinding.Eaton ? SmartVendor.EATON : SmartVendor.SHELLY,
            binding, BlindsPlacement.EXTERNAL, !(binding instanceof BlindBinding.Eaton));
    }
}
