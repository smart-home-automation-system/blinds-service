package cloud.cholewa.blinds.registry;

import cloud.cholewa.blinds.error.BlindsError;
import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.EatonBinding;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.home.model.SmartVendor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlindRegistryTest {

    private final DeviceRegistryClient client = mock(DeviceRegistryClient.class);
    private final BlindRegistry registry = new BlindRegistry(client);

    @Test
    void should_know_the_blinds_of_the_last_refresh() {
        when(client.getBlinds()).thenReturn(Mono.just(List.of(eaton(17, RoomName.KITCHEN, 9), eaton(18, RoomName.KITCHEN, 10))));

        StepVerifier.create(registry.refresh()).verifyComplete();

        StepVerifier.create(registry.find(18))
            .assertNext(blind -> assertThat(blind.id()).isEqualTo(18))
            .verifyComplete();
        StepVerifier.create(registry.inRoom(RoomName.KITCHEN))
            .assertNext(blinds -> assertThat(blinds).extracting(Blind::id).containsExactly(17L, 18L))
            .verifyComplete();
    }

    //a database-service that is away for a moment must not make every blind unknown
    @Test
    void should_keep_the_last_list_when_a_refresh_fails() {
        when(client.getBlinds())
            .thenReturn(Mono.just(List.of(eaton(17, RoomName.KITCHEN, 9))))
            .thenReturn(Mono.error(new RegistryCallException("database-service answered 500")));

        StepVerifier.create(registry.refresh()).verifyComplete();
        StepVerifier.create(registry.refresh()).verifyComplete();

        StepVerifier.create(registry.find(17)).expectNextCount(1).verifyComplete();
    }

    @Test
    void should_drop_a_blind_the_registry_no_longer_holds() {
        when(client.getBlinds())
            .thenReturn(Mono.just(List.of(eaton(17, RoomName.KITCHEN, 9))))
            .thenReturn(Mono.just(List.of()));

        StepVerifier.create(registry.refresh()).verifyComplete();
        StepVerifier.create(registry.refresh()).verifyComplete();

        StepVerifier.create(registry.find(17)).expectError(BlindsException.class).verify();
    }

    //a command right after a start must not be refused because the pod came up before database-service
    @Test
    void should_read_the_registry_on_a_lookup_before_the_first_refresh_succeeded() {
        when(client.getBlinds())
            .thenReturn(Mono.error(new RegistryCallException("database-service answered 503")))
            .thenReturn(Mono.just(List.of(eaton(17, RoomName.KITCHEN, 9))));

        StepVerifier.create(registry.refresh()).verifyComplete();
        StepVerifier.create(registry.find(17)).expectNextCount(1).verifyComplete();
        //known from now on: no read for the next lookup
        StepVerifier.create(registry.find(17)).expectNextCount(1).verifyComplete();

        verify(client, times(2)).getBlinds();
    }

    @Test
    void should_not_find_a_blind_it_does_not_know() {
        when(client.getBlinds()).thenReturn(Mono.just(List.of(eaton(17, RoomName.KITCHEN, 9))));

        StepVerifier.create(registry.find(99))
            .expectErrorSatisfies(e -> assertThat(((BlindsException) e).getError()).isEqualTo(BlindsError.NOT_FOUND_BLINDS))
            .verify();
        StepVerifier.create(registry.inRoom(RoomName.CINEMA))
            .expectErrorSatisfies(e -> assertThat(((BlindsException) e).getError()).isEqualTo(BlindsError.NOT_FOUND_BLINDS))
            .verify();
    }

    //nothing known while the registry cannot be read at all: unknown, not a failure of the device
    @Test
    void should_not_find_anything_while_the_registry_was_never_read() {
        when(client.getBlinds()).thenReturn(Mono.error(new RegistryCallException("database-service answered 503")));

        StepVerifier.create(registry.find(17))
            .expectErrorSatisfies(e -> assertThat(((BlindsException) e).getError()).isEqualTo(BlindsError.NOT_FOUND_BLINDS))
            .verify();
    }

    @Test
    void should_skip_a_device_it_cannot_drive_and_keep_the_rest() {
        final SmartDevice withoutPoint = eaton(19, RoomName.CINEMA, 12).eaton(new EatonBinding());
        when(client.getBlinds()).thenReturn(Mono.just(Arrays.asList(withoutPoint, null, eaton(17, RoomName.KITCHEN, 9))));

        StepVerifier.create(registry.refresh()).verifyComplete();

        StepVerifier.create(registry.find(19)).expectError(BlindsException.class).verify();
        StepVerifier.create(registry.find(17)).expectNextCount(1).verifyComplete();
    }

    //Spring subscribes to the Mono of the schedule again for every run: the read must happen each time
    @Test
    void should_read_the_registry_again_on_every_subscription() {
        when(client.getBlinds()).thenReturn(Mono.just(List.of()));
        final Mono<Void> refresh = new BlindRegistryRefresh(registry).refresh();

        StepVerifier.create(refresh).verifyComplete();
        StepVerifier.create(refresh).verifyComplete();

        verify(client, times(2)).getBlinds();
    }

    private static SmartDevice eaton(final long id, final RoomName room, final int point) {
        return SmartDevice.builder()
            .id(id)
            .name("Blind " + id)
            .room(room)
            .kind(DeviceKind.BLINDS)
            .enabled(true)
            .vendor(SmartVendor.EATON)
            .eaton(new EatonBinding().point(point))
            .build();
    }
}
