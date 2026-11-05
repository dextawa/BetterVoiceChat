package top.dext.bvc.protocol.data;

/**
 * Which transports the server lets clients use.
 */
public enum TransportPolicy {
    /** Try {@link Transport#STREAM} first, fall back to {@link Transport#PLUGIN_MESSAGE}. */
    AUTO(0),
    STREAM_ONLY(1),
    PLUGIN_MESSAGE_ONLY(2);

    private final int id;

    TransportPolicy(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public boolean allows(Transport transport) {
        switch (this) {
            case STREAM_ONLY:
                return transport == Transport.STREAM;
            case PLUGIN_MESSAGE_ONLY:
                return transport == Transport.PLUGIN_MESSAGE;
            default:
                return true;
        }
    }

    public static TransportPolicy byId(int id) {
        switch (id) {
            case 1:
                return STREAM_ONLY;
            case 2:
                return PLUGIN_MESSAGE_ONLY;
            default:
                return AUTO;
        }
    }
}
