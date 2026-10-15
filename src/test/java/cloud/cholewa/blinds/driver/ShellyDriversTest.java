package cloud.cholewa.blinds.driver;

import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.BlindCommand;
import cloud.cholewa.home.model.BlindsPlacement;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.shelly.http.ShellyCallException;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.shelly.http.ShellyGen1Client.RollerCommand;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.model.RollerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShellyDriversTest {

    private static final Blind ROLLER = blind(new BlindBinding.ShellyGen1("10.0.0.21", 1));
    private static final Blind COVER = blind(new BlindBinding.ShellyGen2("10.0.0.22", 0));

    private final ShellyGen1Client gen1Client = mock(ShellyGen1Client.class);
    private final ShellyRpcClient rpcClient = mock(ShellyRpcClient.class);
    private final ShellyGen1Driver gen1Driver = new ShellyGen1Driver(gen1Client);
    private final ShellyGen2Driver gen2Driver = new ShellyGen2Driver(rpcClient);

    @ParameterizedTest
    @CsvSource({"OPEN,OPEN", "CLOSE,CLOSE", "STOP,STOP"})
    void should_send_a_gen1_roller_its_command_on_its_channel(final BlindCommand command, final RollerCommand go) {
        when(gen1Client.roller("10.0.0.21", 1, go)).thenReturn(Mono.just(new RollerStatus()));

        StepVerifier.create(gen1Driver.move(ROLLER, command)).verifyComplete();

        verify(gen1Client).roller("10.0.0.21", 1, go);
    }

    @Test
    void should_send_a_gen1_roller_to_a_position() {
        when(gen1Client.rollerToPosition("10.0.0.21", 1, 40)).thenReturn(Mono.just(new RollerStatus()));

        StepVerifier.create(gen1Driver.moveTo(ROLLER, 40)).verifyComplete();
    }

    @Test
    void should_fail_with_the_device() {
        when(gen1Client.roller("10.0.0.21", 1, RollerCommand.OPEN))
            .thenReturn(Mono.error(new ShellyCallException("GET /roller/1 of 10.0.0.21 timed out")));

        StepVerifier.create(gen1Driver.move(ROLLER, BlindCommand.OPEN)).expectError(ShellyCallException.class).verify();
    }

    @Test
    void should_send_a_gen2_cover_its_rpc_on_its_id() {
        when(rpcClient.coverOpen("10.0.0.22", 0)).thenReturn(Mono.empty());
        when(rpcClient.coverClose("10.0.0.22", 0)).thenReturn(Mono.empty());
        when(rpcClient.coverStop("10.0.0.22", 0)).thenReturn(Mono.empty());
        when(rpcClient.coverGoToPosition("10.0.0.22", 0, 75)).thenReturn(Mono.empty());

        StepVerifier.create(gen2Driver.move(COVER, BlindCommand.OPEN)).verifyComplete();
        StepVerifier.create(gen2Driver.move(COVER, BlindCommand.CLOSE)).verifyComplete();
        StepVerifier.create(gen2Driver.move(COVER, BlindCommand.STOP)).verifyComplete();
        StepVerifier.create(gen2Driver.moveTo(COVER, 75)).verifyComplete();

        verify(rpcClient).coverOpen("10.0.0.22", 0);
        verify(rpcClient).coverClose("10.0.0.22", 0);
        verify(rpcClient).coverStop("10.0.0.22", 0);
        verify(rpcClient).coverGoToPosition("10.0.0.22", 0, 75);
    }

    private static Blind blind(final BlindBinding binding) {
        return new Blind(21, "Office window", RoomName.OFFICE, SmartVendor.SHELLY, binding,
            BlindsPlacement.INTERNAL, true);
    }
}
