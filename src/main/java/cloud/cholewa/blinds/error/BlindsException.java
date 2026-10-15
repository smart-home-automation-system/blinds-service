package cloud.cholewa.blinds.error;

import cloud.cholewa.blinds.model.Blind;
import lombok.Getter;
import org.springframework.http.HttpStatus;

//A command that was not carried out, or may not have been. The status says whose problem it is,
//the error names the cause. The message is for the log; it is answered as details only for a 4xx -
//for a failed device it quotes the device, and that never goes into an answer
@Getter
public class BlindsException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final BlindsError error;

    public BlindsException(final HttpStatus httpStatus, final BlindsError error, final String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.error = error;
    }

    public static BlindsException notFound(final String what) {
        return new BlindsException(HttpStatus.NOT_FOUND, BlindsError.NOT_FOUND_BLINDS, what);
    }

    public static BlindsException unsupportedPosition(final Blind blind) {
        return new BlindsException(
            HttpStatus.BAD_REQUEST,
            BlindsError.UNSUPPORTED_POSITION,
            "blind " + blind.id() + " has no positioning"
        );
    }

    public static BlindsException protectionActive(final Blind blind, final String reason) {
        return new BlindsException(
            HttpStatus.CONFLICT,
            BlindsError.PROTECTION_ACTIVE,
            "blind " + blind.id() + ": " + reason
        );
    }

    public static BlindsException deviceFailed(final Blind blind, final Throwable cause) {
        return new BlindsException(
            HttpStatus.BAD_GATEWAY,
            BlindsError.DEVICE_FAILED,
            "blind " + blind.id() + " (" + blind.name() + "): " + describe(cause)
        );
    }

    //the type alone when an exception has no message - "null" names nothing
    private static String describe(final Throwable cause) {
        return cause.getMessage() == null
            ? cause.getClass().getSimpleName()
            : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
