package top.dext.bvc.protocol.data;

/**
 * How audio travels between a client and the proxy.
 */
public enum Transport {
    /** "A": a second TCP connection to the Minecraft port, taken over by the proxy. */
    STREAM(0),
    /** "B": plugin messages inside the Minecraft connection. */
    PLUGIN_MESSAGE(1);

    private final int id;

    Transport(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public int bit() {
        return 1 << id;
    }

    public static Transport byId(int id) {
        return id == 0 ? STREAM : PLUGIN_MESSAGE;
    }
}
