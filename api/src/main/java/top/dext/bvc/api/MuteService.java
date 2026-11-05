package top.dext.bvc.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Voice mutes, enforced by the proxy for every sub-server. Thread-safe.
 * <p>
 * Example (from a LiteBans hook on the proxy):
 * <pre>{@code
 * BvcProxy.get().mutes().mute(uuid, name, MuteScope.global(), 3_600_000L, "Spam", "LiteBans");
 * }</pre>
 */
public interface MuteService {

    /**
     * Mutes a player. An existing mute with the same scope is replaced.
     *
     * @param durationMillis 0 for permanent
     */
    Mute mute(UUID target, String targetName, MuteScope scope, long durationMillis, String reason, String operator);

    /**
     * Lifts the player's mute in exactly this scope.
     *
     * @return whether a mute was removed
     */
    boolean unmute(UUID target, MuteScope scope, String operator);

    /** The mute that currently silences the player on {@code server}, if any (global mutes first). */
    Optional<Mute> activeMute(UUID target, String server);

    /** All currently active mutes of the player. */
    List<Mute> activeMutes(UUID target);

    /** Network-wide "everyone is muted" switch (players with the bypass permission still talk). */
    boolean isGlobalMuteEnabled();

    void setGlobalMuteEnabled(boolean enabled, String operator);

    /** "Everyone on this sub-server is muted" switch. */
    boolean isServerMuted(String server);

    void setServerMuted(String server, boolean muted, String operator);

    void addListener(MuteListener listener);

    void removeListener(MuteListener listener);
}
