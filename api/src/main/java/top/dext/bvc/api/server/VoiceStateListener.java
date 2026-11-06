package top.dext.bvc.api.server;

import java.util.UUID;

/**
 * Sub-server voice events. Register with {@link BvcServerApi#addListener}. Called off the main
 * thread; schedule back to it before touching the world.
 */
public interface VoiceStateListener {

    /** The proxy reported that this player now has (or no longer has) a working voice connection. */
    default void onVoiceChanged(UUID player, boolean hasVoice) {
    }
}
