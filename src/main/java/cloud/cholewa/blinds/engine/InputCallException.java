package cloud.cholewa.blinds.engine;

//a read of an input of the schedules (the calendar, the weather) that did not end with what was
//asked for; never answered - the engine treats the input as unknown
public class InputCallException extends RuntimeException {

    public InputCallException(final String message) {
        super(message);
    }
}
