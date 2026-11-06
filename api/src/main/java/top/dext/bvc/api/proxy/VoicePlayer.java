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

    /** Priority from the proxy permission {@code bvc.priority.<n>} (sub-servers may raise it). */
    int priority();

    /** The player muted their own microphone in the client. */
    boolean isMicrophoneMuted();

    /** The player turned off listening in the client. */
    boolean isDeafened();

    /** {@code STREAM} (A), {@code PLUGIN_MESSAGE} (B) or {@code null} while not connected. */
    String transportName();
}
