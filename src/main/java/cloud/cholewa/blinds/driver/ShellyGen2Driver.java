package cloud.cholewa.blinds.driver;

import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.BlindCommand;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

//A Gen2+ cover: Cover.Open, Cover.Close, Cover.Stop, Cover.GoToPosition with id = channel
@Component
@RequiredArgsConstructor
public class ShellyGen2Driver implements BlindsDriver {

    private final ShellyRpcClient shellyRpcClient;

    @Override
    public Mono<Void> move(final Blind blind, final BlindCommand command) {
        final BlindBinding.ShellyGen2 cover = binding(blind);
        return switch (command) {
            case OPEN -> shellyRpcClient.coverOpen(cover.address(), cover.channel());
            case CLOSE -> shellyRpcClient.coverClose(cover.address(), cover.channel());
            case STOP -> shellyRpcClient.coverStop(cover.address(), cover.channel());
        };
    }

    @Override
    public Mono<Void> moveTo(final Blind blind, final int position) {
        final BlindBinding.ShellyGen2 cover = binding(blind);
        return shellyRpcClient.coverGoToPosition(cover.address(), cover.channel(), position);
    }

    private static BlindBinding.ShellyGen2 binding(final Blind blind) {
        return (BlindBinding.ShellyGen2) blind.binding();
    }
}
