package cloud.cholewa.blinds.driver;

import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindCommand;
import reactor.core.publisher.Mono;

/**
 * One kind of actuator. A command completes once the device, or amx-service for an Eaton blind,
 * took it - never once the blind got there: where it is comes from what the device reports. A
 * call that did not end with that is an error of whatever kind; the service maps it.
 */
public interface BlindsDriver {

    Mono<Void> move(Blind blind, BlindCommand command);

    /**
     * @param position 0 (closed) to 100 (open), for a blind with positioning
     */
    Mono<Void> moveTo(Blind blind, int position);
}
