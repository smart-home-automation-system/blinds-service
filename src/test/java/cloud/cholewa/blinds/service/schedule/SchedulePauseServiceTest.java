package cloud.cholewa.blinds.service.schedule;

import cloud.cholewa.blinds.database.model.SchedulePauseEntity;
import cloud.cholewa.blinds.database.repository.SchedulePauseRepository;
import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.registry.BlindRegistry;
import cloud.cholewa.home.model.BlindsPlacement;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The pause of the schedules of a blind (HAS-232): only for a blind of the registry, its start to the
//second, and the reads of the blinds served the pauses last read while the database is away
class SchedulePauseServiceTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 17, 20, 15, 7);
    private static final Blind KITCHEN = new Blind(17, "Kitchen window", RoomName.KITCHEN, SmartVendor.EATON,
        new BlindBinding.Eaton(9), BlindsPlacement.EXTERNAL, false);

    private final BlindRegistry blindRegistry = mock(BlindRegistry.class);
    private final SchedulePauseRepository repository = mock(SchedulePauseRepository.class);
    private final SchedulePauseService sut = new SchedulePauseService(blindRegistry, repository,
        Clock.fixed(NOW.plusNanos(400_000_000).atZone(HOUSE).toInstant(), HOUSE));

    @Test
    void should_pause_a_blind_of_the_registry_from_now() {
        when(blindRegistry.find(17)).thenReturn(Mono.just(KITCHEN));
        when(repository.pause(17, NOW)).thenReturn(Mono.just(1));

        sut.pause(17).as(StepVerifier::create).verifyComplete();

        verify(repository).pause(17, NOW);
    }

    @Test
    void should_refuse_a_blind_the_registry_does_not_know() {
        when(blindRegistry.find(99)).thenReturn(Mono.error(BlindsException.notFound("no blind with id 99")));

        sut.pause(99).as(StepVerifier::create).expectError(BlindsException.class).verify();
        sut.resume(99).as(StepVerifier::create).expectError(BlindsException.class).verify();

        verify(repository, never()).pause(anyLong(), any());
    }

    @Test
    void should_resume_a_paused_blind_and_change_nothing_for_another() {
        final SchedulePauseEntity pause = new SchedulePauseEntity(17, NOW);
        when(blindRegistry.find(17)).thenReturn(Mono.just(KITCHEN));
        when(repository.findById(17L)).thenReturn(Mono.just(pause), Mono.empty());
        when(repository.delete(pause)).thenReturn(Mono.empty());

        sut.resume(17).as(StepVerifier::create).verifyComplete();
        sut.resume(17).as(StepVerifier::create).verifyComplete();

        verify(repository).delete(pause);
    }

    @Test
    void should_answer_the_reads_the_pauses_last_read_while_the_database_is_away() {
        when(repository.findAll()).thenReturn(Flux.just(new SchedulePauseEntity(17, NOW)),
            Flux.error(new IllegalStateException("no connection")));

        sut.paused().as(StepVerifier::create).expectNext(Map.of(17L, NOW)).verifyComplete();
        sut.pausedAsLastKnown().as(StepVerifier::create).expectNext(Map.of(17L, NOW)).verifyComplete();
    }

    //the engine is strict: without the pauses it fires nothing
    @Test
    void should_fail_the_strict_read_while_the_database_is_away() {
        when(repository.findAll()).thenReturn(Flux.error(new IllegalStateException("no connection")));

        sut.paused().as(StepVerifier::create).expectError(IllegalStateException.class).verify();
    }
}
