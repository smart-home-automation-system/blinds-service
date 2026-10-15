package cloud.cholewa.blinds.driver;

import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.BlindCommand;
import cloud.cholewa.shelly.http.ShellyGen1Client;
import cloud.cholewa.shelly.http.ShellyGen1Client.RollerCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

//A Gen1 roller: GET /roller/<channel>?go=open|close|stop|to_pos. Its answer is the status at the
//start of the movement; it is not read here - the state comes from the device (HAS-245)
@Component
@RequiredArgsConstructor
public class ShellyGen1Driver implements BlindsDriver {

    private final ShellyGen1Client shellyGen1Client;

    @Override
    public Mono<Void> move(final Blind blind, final BlindCommand command) {
        final BlindBinding.ShellyGen1 roller = binding(blind);
        return shellyGen1Client.roller(roller.address(), roller.channel(), rollerCommand(command)).then();
    }

    @Override
    public Mono<Void> moveTo(final Blind blind, final int position) {
        final BlindBinding.ShellyGen1 roller = binding(blind);
        return shellyGen1Client.rollerToPosition(roller.address(), roller.channel(), position).then();
    }

    private static RollerCommand rollerCommand(final BlindCommand command) {
        return switch (command) {
            case OPEN -> RollerCommand.OPEN;
            case CLOSE -> RollerCommand.CLOSE;
            case STOP -> RollerCommand.STOP;
        };
    }

    private static BlindBinding.ShellyGen1 binding(final Blind blind) {
        return (BlindBinding.ShellyGen1) blind.binding();
    }
}
