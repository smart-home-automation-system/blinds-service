package cloud.cholewa.blinds.state;

import cloud.cholewa.blinds.model.BlindReading;
import cloud.cholewa.blinds.model.BlindState;
import cloud.cholewa.blinds.model.Direction;
import cloud.cholewa.shelly.model.CoverStatus;
import cloud.cholewa.shelly.model.RollerStatus;

import java.util.Optional;

//What each kind of device report says, as a reading. A word the service does not know - a state a
//firmware added, "calibrating" - is no reading at all: guessing would store a state the blind is
//not in. A position comes only from a device that says it knows it, and only within 0..100
public final class BlindReadings {

    private BlindReadings() {
    }

    //a Gen2+ cover - the MQTT status and the answer of Cover.GetStatus are the same model
    public static Optional<BlindReading> ofCover(final CoverStatus cover) {
        final BlindState state = switch (word(cover.getState())) {
            case "open" -> BlindState.OPEN;
            case "closed" -> BlindState.CLOSED;
            case "opening" -> BlindState.OPENING;
            case "closing" -> BlindState.CLOSING;
            case "stopped" -> BlindState.STOPPED;
            default -> null;
        };
        if (state == null) {
            return Optional.empty();
        }
        final Integer position = Boolean.TRUE.equals(cover.getPosControl()) ? position(cover.getCurrentPos()) : null;
        return Optional.of(new BlindReading(state, position, direction(cover.getLastDirection()), cover.getSource()));
    }

    //A Gen1 roller of /status: its state is what the motor does (open, close, stop), not where the
    //roller is. At rest a calibrated roller tells the end by its position
    public static Optional<BlindReading> ofRoller(final RollerStatus roller) {
        final Integer position = Boolean.TRUE.equals(roller.getPositioning()) ? position(roller.getCurrentPos()) : null;
        final BlindState state = switch (word(roller.getState())) {
            case "open" -> BlindState.OPENING;
            case "close" -> BlindState.CLOSING;
            case "stop" -> position == null ? BlindState.STOPPED
                : position == 100 ? BlindState.OPEN
                : position == 0 ? BlindState.CLOSED
                : BlindState.STOPPED;
            default -> null;
        };
        if (state == null) {
            return Optional.empty();
        }
        return Optional.of(new BlindReading(state, position, direction(roller.getLastDirection()), roller.getSource()));
    }

    //The roller_*_url actions of a Gen1 device (HAS-255, seen on a Shelly 2.5 on v1.14.0): open and
    //close when the roller STARTS that way - not when it reached the end, as the first version read
    //them, which showed a blind closed for the half minute it was still closing - and stop when it
    //stopped, at an end or anywhere. A movement has no position yet; where a stop left the roller is
    //read from the device at once (BlindsQueryService) - this reading is what is kept when it does
    //not answer
    public static BlindReading ofGen1Action(final Gen1Action action) {
        return switch (action) {
            case OPEN -> new BlindReading(BlindState.OPENING, null, Direction.OPEN, null);
            case CLOSE -> new BlindReading(BlindState.CLOSING, null, Direction.CLOSE, null);
            case STOP -> new BlindReading(BlindState.STOPPED, null, null, null);
        };
    }

    //The status of an Eaton blinds actuator, its info short (amx-service, HAS-229): 1 open and 2
    //close when it starts, 0 when it stops - at an end or anywhere, it does not say which
    public static Optional<BlindReading> ofEatonStatus(final Integer value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value) {
            case 0 -> Optional.of(new BlindReading(BlindState.STOPPED, null, null, null));
            case 1 -> Optional.of(new BlindReading(BlindState.OPENING, null, Direction.OPEN, null));
            case 2 -> Optional.of(new BlindReading(BlindState.CLOSING, null, Direction.CLOSE, null));
            default -> Optional.empty();
        };
    }

    private static String word(final String value) {
        return value == null ? "" : value;
    }

    private static Direction direction(final String value) {
        return switch (word(value)) {
            case "open" -> Direction.OPEN;
            case "close" -> Direction.CLOSE;
            default -> null;
        };
    }

    private static Integer position(final Integer value) {
        return value != null && value >= 0 && value <= 100 ? value : null;
    }
}
