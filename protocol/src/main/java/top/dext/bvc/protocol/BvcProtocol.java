package top.dext.bvc.protocol;

/**
 * Constants shared by every side of the BVC protocol.
 */
public final class BvcProtocol {

    /** Bumped on any incompatible change to packets or the stream handshake. */
    public static final int VERSION = 1;

    /** Client &lt;-&gt; proxy: control packets, plus audio when the plugin-message transport is used. */
    public static final String CHANNEL_VOICE = "bvc:voice";
    /** Backend -&gt; proxy: voice zones, membership, neighbour tables. */
    public static final String CHANNEL_SERVER = "bvc:server";
    /** Proxy -&gt; backend: player id bindings and resync requests. */
    public static final String CHANNEL_PROXY = "bvc:proxy";

    /**
     * Appended to the server address of the fake Minecraft handshake that opens a stream
     * connection, the same trick Forge uses with {@code \0FML\0}.
     */
    public static final String HANDSHAKE_MARKER = "\0BVC\0";

    public static final int SAMPLE_RATE = 48_000;
    public static final int FRAME_MILLIS = 20;
    public static final int FRAME_SAMPLES = SAMPLE_RATE / 1000 * FRAME_MILLIS;
    /** Largest legal single Opus packet. */
    public static final int MAX_OPUS_PACKET = 1275;

    /** Upper bound for one length-prefixed frame on the stream transport. */
    public static final int MAX_STREAM_FRAME = 1 << 21;
    /** Upper bound for one plugin message payload. */
    public static final int MAX_PLUGIN_MESSAGE = 32_000;

    public static final int TOKEN_LENGTH = 32;

    private BvcProtocol() {
    }
}
