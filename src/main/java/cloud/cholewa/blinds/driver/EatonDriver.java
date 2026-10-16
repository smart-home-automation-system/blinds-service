package cloud.cholewa.blinds.driver;

import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.BlindCommand;
import cloud.cholewa.commons.error.DownstreamErrors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

//An Eaton blinds actuator: POST /home/amx/eaton/blinds/{point}?command= of amx-service (HAS-228).
//Its 202 says the frame was handed to the gateway. Every refusal of amx-service - a point it does
//not know, the frame server away, a frame that may have gone out - is a failed command here, with
//its status and codes in the message for the log. An Eaton actuator has no positions
@Component
public class EatonDriver implements BlindsDriver {

    private final WebClient amxWebClient;

    public EatonDriver(@Qualifier("amxWebClient") final WebClient amxWebClient) {
        this.amxWebClient = amxWebClient;
    }

    @Override
    public Mono<Void> move(final Blind blind, final BlindCommand command) {
        final BlindBinding.Eaton eaton = (BlindBinding.Eaton) blind.binding();

        return amxWebClient
            .post()
            .uri(uriBuilder -> uriBuilder
                .path("/home/amx/eaton/blinds/{point}")
                .queryParam("command", command.value())
                .build(eaton.point())
            )
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
                .map(error -> new AmxCallException(
                    "amx-service answered " + error.status().value() + " " + error.errors())))
            .toBodilessEntity()
            .then();
    }

    //asks the actuator for its state (HAS-291): POST .../blinds/{point}/status of amx-service, which
    //sends 0B. The answer arrives as the usual status event, not in this call
    public Mono<Void> requestStatus(final Blind blind) {
        final BlindBinding.Eaton eaton = (BlindBinding.Eaton) blind.binding();

        return amxWebClient
            .post()
            .uri("/home/amx/eaton/blinds/{point}/status", eaton.point())
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
                .map(error -> new AmxCallException(
                    "amx-service answered " + error.status().value() + " " + error.errors())))
            .toBodilessEntity()
            .then();
    }

    @Override
    public Mono<Void> moveTo(final Blind blind, final int position) {
        return Mono.error(() -> BlindsException.unsupportedPosition(blind));
    }

    static class AmxCallException extends RuntimeException {

        AmxCallException(final String message) {
            super(message);
        }
    }
}
