package cloud.cholewa.blinds.state;

import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.BlindReading;
import cloud.cholewa.blinds.model.BlindState;
import cloud.cholewa.blinds.model.Direction;
import cloud.cholewa.home.model.BlindsPlacement;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.shelly.model.CoverStatus;
import cloud.cholewa.shelly.model.RollerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class BlindReadingsTest {

    @ParameterizedTest
    @CsvSource({"open,OPEN", "closed,CLOSED", "opening,OPENING", "closing,CLOSING", "stopped,STOPPED"})
    void should_read_the_state_of_a_cover(final String word, final BlindState state) {
        assertThat(BlindReadings.ofCover(cover(word, true, 40))).get().extracting(BlindReading::state).isEqualTo(state);
    }

    @Test
    void should_read_what_a_cover_reports() {
        final CoverStatus cover = cover("stopped", true, 40);
        cover.setLastDirection("close");
        cover.setSource("SHC");

        assertThat(BlindReadings.ofCover(cover))
            .contains(new BlindReading(BlindState.STOPPED, 40, Direction.CLOSE, "SHC"));
    }

    //a position the device does not control is no position
    @Test
    void should_leave_the_position_out_of_a_cover_without_position_control() {
        assertThat(BlindReadings.ofCover(cover("stopped", false, 40))).get()
            .extracting(BlindReading::position).isNull();
        assertThat(BlindReadings.ofCover(cover("stopped", null, 40))).get()
            .extracting(BlindReading::position).isNull();
        assertThat(BlindReadings.ofCover(cover("stopped", true, 140))).get()
            .extracting(BlindReading::position).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"calibrating", "OPEN", "half"})
    void should_not_read_a_state_it_does_not_know(final String word) {
        assertThat(BlindReadings.ofCover(cover(word, true, 40))).isEmpty();
        assertThat(BlindReadings.ofRoller(roller(word, true, 40))).isEmpty();
    }

    @Test
    void should_not_read_a_cover_without_a_state() {
        assertThat(BlindReadings.ofCover(cover(null, true, 40))).isEmpty();
    }

    //the state of a Gen1 roller is what its motor does
    @ParameterizedTest
    @CsvSource({
        "open,true,50,OPENING", "close,true,50,CLOSING",
        "stop,true,100,OPEN", "stop,true,0,CLOSED", "stop,true,37,STOPPED",
        "stop,false,100,STOPPED"
    })
    void should_read_the_state_of_a_roller(final String word, final boolean positioning, final int position,
                                           final BlindState state) {
        assertThat(BlindReadings.ofRoller(roller(word, positioning, position))).get()
            .extracting(BlindReading::state).isEqualTo(state);
    }

    @Test
    void should_read_the_position_of_a_calibrated_roller_only() {
        assertThat(BlindReadings.ofRoller(roller("stop", true, 37))).get().extracting(BlindReading::position).isEqualTo(37);
        assertThat(BlindReadings.ofRoller(roller("stop", false, 37))).get().extracting(BlindReading::position).isNull();
    }

    @Test
    void should_take_an_action_as_the_end_the_roller_reached() {
        assertThat(BlindReadings.ofGen1Action(gen1(true), Gen1Action.OPEN))
            .isEqualTo(new BlindReading(BlindState.OPEN, 100, Direction.OPEN, null));
        assertThat(BlindReadings.ofGen1Action(gen1(true), Gen1Action.CLOSE))
            .isEqualTo(new BlindReading(BlindState.CLOSED, 0, Direction.CLOSE, null));
        assertThat(BlindReadings.ofGen1Action(gen1(false), Gen1Action.OPEN))
            .isEqualTo(new BlindReading(BlindState.OPEN, null, Direction.OPEN, null));
        assertThat(BlindReadings.ofGen1Action(gen1(true), Gen1Action.STOP))
            .isEqualTo(new BlindReading(BlindState.STOPPED, null, null, null));
    }

    @Test
    void should_read_the_status_of_an_eaton_actuator() {
        assertThat(BlindReadings.ofEatonStatus(1)).contains(new BlindReading(BlindState.OPENING, null, Direction.OPEN, null));
        assertThat(BlindReadings.ofEatonStatus(2)).contains(new BlindReading(BlindState.CLOSING, null, Direction.CLOSE, null));
        assertThat(BlindReadings.ofEatonStatus(0)).contains(new BlindReading(BlindState.STOPPED, null, null, null));
        assertThat(BlindReadings.ofEatonStatus(3)).isEmpty();
        assertThat(BlindReadings.ofEatonStatus(null)).isEmpty();
    }

    private static CoverStatus cover(final String state, final Boolean posControl, final Integer position) {
        final CoverStatus cover = new CoverStatus();
        cover.setId(0);
        cover.setState(state);
        cover.setPosControl(posControl);
        cover.setCurrentPos(position);
        return cover;
    }

    private static RollerStatus roller(final String state, final boolean positioning, final int position) {
        final RollerStatus roller = new RollerStatus();
        roller.setState(state);
        roller.setPositioning(positioning);
        roller.setCurrentPos(position);
        return roller;
    }

    private static Blind gen1(final boolean positioning) {
        return new Blind(52, "Office window", RoomName.OFFICE, SmartVendor.SHELLY,
            new BlindBinding.ShellyGen1("10.0.0.59", 0), BlindsPlacement.EXTERNAL, positioning);
    }
}
