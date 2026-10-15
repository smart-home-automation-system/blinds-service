package cloud.cholewa.blinds.registry;

import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.home.model.EatonBinding;
import cloud.cholewa.home.model.ShellyBinding;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.home.model.SmartVendor;

import java.util.Optional;

//A device of the registry as a blind - or nothing, when the registry holds too little to drive it:
//no id or room, a Shelly without its address or generation, an Eaton device without its point, a
//vendor the service has no driver for. The registry validates what it stores, so this is a device
//written before a rule existed; the caller skips it and names it at WARN
final class BlindMapper {

    private BlindMapper() {
    }

    static Optional<Blind> toBlind(final SmartDevice device) {
        if (device.getId() == null || device.getRoom() == null || device.getVendor() == null) {
            return Optional.empty();
        }

        //an Eaton actuator has no positions whatever the registry says: one place decides it
        return binding(device).map(binding -> new Blind(
            device.getId(),
            device.getName(),
            device.getRoom(),
            device.getVendor(),
            binding,
            device.getPlacement(),
            Boolean.TRUE.equals(device.getPositioning()) && !(binding instanceof BlindBinding.Eaton)
        ));
    }

    private static Optional<BlindBinding> binding(final SmartDevice device) {
        if (device.getVendor() == SmartVendor.SHELLY) {
            return shelly(device.getShelly());
        }
        if (device.getVendor() == SmartVendor.EATON) {
            return eaton(device.getEaton());
        }
        return Optional.empty();
    }

    //a device with one roller or cover leaves the channel out: it is 0
    private static Optional<BlindBinding> shelly(final ShellyBinding shelly) {
        if (shelly == null || shelly.getAddress() == null || shelly.getAddress().isBlank()
            || shelly.getGeneration() == null) {
            return Optional.empty();
        }

        final int channel = shelly.getChannel() == null ? 0 : shelly.getChannel();

        return Optional.of(shelly.getGeneration() >= 2
            ? new BlindBinding.ShellyGen2(shelly.getAddress(), channel, shelly.getDeviceId())
            : new BlindBinding.ShellyGen1(shelly.getAddress(), channel));
    }

    //the gateway is not named: amx-service finds it in the same registry by the point
    private static Optional<BlindBinding> eaton(final EatonBinding eaton) {
        if (eaton == null || eaton.getPoint() == null) {
            return Optional.empty();
        }
        return Optional.of(new BlindBinding.Eaton(eaton.getPoint()));
    }
}
