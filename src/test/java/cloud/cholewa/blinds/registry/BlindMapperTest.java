package cloud.cholewa.blinds.registry;

import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.home.model.BlindsPlacement;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.EatonBinding;
import cloud.cholewa.home.model.EatonGatewayType;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.ShellyBinding;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.home.model.SmartVendor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlindMapperTest {

    @Test
    void should_take_a_gen1_shelly_as_a_roller() {
        final SmartDevice device = shelly(1, 1).positioning(true).placement(BlindsPlacement.INTERNAL);

        assertThat(BlindMapper.toBlind(device)).contains(new Blind(
            21, "Office window", RoomName.OFFICE, SmartVendor.SHELLY,
            new BlindBinding.ShellyGen1("10.0.0.21", 1), BlindsPlacement.INTERNAL, true));
    }

    @Test
    void should_take_a_gen2_or_later_shelly_as_a_cover() {
        assertThat(BlindMapper.toBlind(shelly(2, 0))).get().extracting(Blind::binding)
            .isEqualTo(new BlindBinding.ShellyGen2("10.0.0.21", 0, null));
        assertThat(BlindMapper.toBlind(shelly(4, 0))).get().extracting(Blind::binding)
            .isEqualTo(new BlindBinding.ShellyGen2("10.0.0.21", 0, null));
    }

    //the id its MQTT topics are published under, to tell its events from the others of the queue
    @Test
    void should_keep_the_device_id_of_a_cover() {
        final SmartDevice device = shelly(2, 0);
        device.getShelly().deviceId("shellyplus2pm-08a6f77e5e34");

        assertThat(BlindMapper.toBlind(device)).get().extracting(Blind::binding)
            .isEqualTo(new BlindBinding.ShellyGen2("10.0.0.21", 0, "shellyplus2pm-08a6f77e5e34"));
    }

    //a device with one roller or cover leaves the channel out
    @Test
    void should_take_channel_0_when_the_registry_names_none() {
        assertThat(BlindMapper.toBlind(shelly(1, null))).get().extracting(Blind::binding)
            .isEqualTo(new BlindBinding.ShellyGen1("10.0.0.21", 0));
    }

    @Test
    void should_take_an_eaton_device_by_its_point() {
        final SmartDevice device = base(17, SmartVendor.EATON)
            .eaton(new EatonBinding().gateway(EatonGatewayType.BLINDS).point(9));

        assertThat(BlindMapper.toBlind(device)).get().extracting(Blind::binding, Blind::positioning)
            .containsExactly(new BlindBinding.Eaton(9), false);
    }

    //amx-service has no positions for a blinds actuator
    @Test
    void should_never_give_an_eaton_actuator_positioning() {
        final SmartDevice device = base(17, SmartVendor.EATON).eaton(new EatonBinding().point(9)).positioning(true);

        assertThat(BlindMapper.toBlind(device)).get().extracting(Blind::positioning).isEqualTo(false);
    }

    @Test
    void should_skip_what_cannot_be_driven() {
        assertThat(BlindMapper.toBlind(base(1, SmartVendor.SHELLY))).isEmpty();
        assertThat(BlindMapper.toBlind(base(1, SmartVendor.SHELLY).shelly(new ShellyBinding().generation(1)))).isEmpty();
        assertThat(BlindMapper.toBlind(base(1, SmartVendor.SHELLY).shelly(new ShellyBinding().address(" ").generation(1))))
            .isEmpty();
        assertThat(BlindMapper.toBlind(base(1, SmartVendor.SHELLY).shelly(new ShellyBinding().address("10.0.0.1"))))
            .isEmpty();
        assertThat(BlindMapper.toBlind(base(1, SmartVendor.EATON).eaton(new EatonBinding()))).isEmpty();
        assertThat(BlindMapper.toBlind(base(1, SmartVendor.OTHER))).isEmpty();
        assertThat(BlindMapper.toBlind(shelly(1, 0).room(null))).isEmpty();
        assertThat(BlindMapper.toBlind(SmartDevice.builder().name("No id").room(RoomName.OFFICE).vendor(SmartVendor.EATON)
            .eaton(new EatonBinding().point(9)).build())).isEmpty();
    }

    private static SmartDevice shelly(final Integer generation, final Integer channel) {
        return base(21, SmartVendor.SHELLY)
            .shelly(new ShellyBinding().address("10.0.0.21").generation(generation).channel(channel));
    }

    private static SmartDevice base(final long id, final SmartVendor vendor) {
        return SmartDevice.builder()
            .id(id)
            .name("Office window")
            .room(RoomName.OFFICE)
            .kind(DeviceKind.BLINDS)
            .enabled(true)
            .vendor(vendor)
            .build();
    }
}
