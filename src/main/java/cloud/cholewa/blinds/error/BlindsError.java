package cloud.cholewa.blinds.error;

import cloud.cholewa.commons.error.model.ErrorId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

//The causes of a command that was not carried out. Their names are the codes of the answer
//(ErrorId.codeOf) - wire contract for a caller that branches on them, the dashboard first of all,
//pinned by BlindsErrorTest
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum BlindsError implements ErrorId {

    NOT_FOUND_BLINDS("Blinds not found"),
    UNSUPPORTED_POSITION("Blind cannot be sent to a position"),
    //raised by the wind and frost protection (HAS-249)
    PROTECTION_ACTIVE("Blinds protection is active"),
    DEVICE_FAILED("Blind did not take the command");

    private final String description;
}
