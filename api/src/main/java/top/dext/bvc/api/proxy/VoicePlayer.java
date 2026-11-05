package top.dext.bvc.api.proxy;

import java.util.UUID;

/**
 * Read-only view of an online player's voice connection.
 */
public interface VoicePlayer {

    UUID uuid();

    String name();

    /** Whether the client mod is installed and the voice transport is connected. */
    boolean isVoiceConnected();

    /** Current sub-server, or {@code null} while switching. */
    String server();

    /** Whether the player sent audio in the last few hundred milliseconds. */
    boolean isSpeaking();

    String modVersion();
}
