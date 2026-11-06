package top.dext.bvc.api.proxy;

/**
 * Proxy-side voice events. Register with {@link BvcProxyApi#addListener}. Called on network or
 * scheduler threads; hand heavy work off.
 */
public interface VoiceListener {

    /** The player's client mod finished connecting voice (or reconnected after a drop). */
    default void onVoiceConnected(VoicePlayer player) {
    }

    /** The player's voice connection went away (left the network, or the stream dropped). */
    default void onVoiceDisconnected(VoicePlayer player) {
    }

    /** A player filed a report; its evidence is ready to review. */
    default void onReport(VoiceReport report) {
    }
}
