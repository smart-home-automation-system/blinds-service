package cloud.cholewa.blinds.error.processor;

import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.commons.error.model.ErrorId;
import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import cloud.cholewa.commons.error.processor.ExceptionProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.util.Collections;

//Answers a command or a read that was not carried out with the status and the code of its cause.
//A 5xx - a device that did not take the command, a registry never read - is logged at ERROR, where
//the alerts look, with what the device said; the answer carries the status and the code alone. A
//4xx is the caller's, a WARN, and its details are answered
@Slf4j
public class BlindsExceptionProcessor implements ExceptionProcessor {

    @Override
    public Errors apply(final Throwable throwable) {
        final BlindsException exception = (BlindsException) throwable;

        final HttpStatus status = exception.getHttpStatus();
        final String code = ErrorId.codeOf(exception.getError());
        final boolean serverError = status.is5xxServerError();

        if (serverError) {
            log.error("Blinds request failed ({}, {}): {}", status.value(), code, exception.getMessage());
        } else {
            log.warn("Blinds request refused ({}, {}): {}", status.value(), code, exception.getMessage());
        }

        return Errors.builder()
            .httpStatus(status)
            .errors(Collections.singleton(
                ErrorMessage.builder()
                    .message(exception.getError().getDescription())
                    .details(serverError ? null : exception.getMessage())
                    .code(code)
                    .build()
            ))
            .build();
    }
}
