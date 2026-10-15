package cloud.cholewa.blinds.driver;

import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

//the driver of a blind, by the kind of its binding; the binding is sealed, so a new kind does not
//compile until it has a driver here
@Component
@RequiredArgsConstructor
public class BlindsDrivers {

    private final ShellyGen1Driver shellyGen1Driver;
    private final ShellyGen2Driver shellyGen2Driver;
    private final EatonDriver eatonDriver;

    public BlindsDriver of(final Blind blind) {
        return switch (blind.binding()) {
            case BlindBinding.ShellyGen1 roller -> shellyGen1Driver;
            case BlindBinding.ShellyGen2 cover -> shellyGen2Driver;
            case BlindBinding.Eaton eaton -> eatonDriver;
        };
    }
}
