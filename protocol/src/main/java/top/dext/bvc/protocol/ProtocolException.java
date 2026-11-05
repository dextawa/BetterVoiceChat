package top.dext.bvc.protocol;

/**
 * Thrown when bytes on the wire do not form a valid BVC packet.
 */
public class ProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
