package cloud.cholewa.blinds.model;

/**
 * How a blind is driven - one kind of actuator each, so a driver is chosen by the type alone.
 */
public sealed interface BlindBinding {

    /** A Shelly Gen1 roller: {@code /roller/<channel>} on the device. */
    record ShellyGen1(String address, int channel) implements BlindBinding {
    }

    /** A Shelly Gen2+ cover: {@code Cover.*} with {@code id = channel}. */
    record ShellyGen2(String address, int channel) implements BlindBinding {
    }

    /** An Eaton blinds actuator on a data point, moved through amx-service. */
    record Eaton(int point) implements BlindBinding {
    }
}
