package top.dext.bvc.api.server;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import top.dext.bvc.api.MuteScope;

/**
 * Entry point for plugins on a sub-server, typically minigames.
 * <p>
 * Voice never leaves the sub-server. Inside it, a <b>room</b> isolates a set of players (for
 * example one arena) with its own {@link ZoneSettings}; players not in any room are in the
 * sub-server's default zone from the config. A <b>group</b> overrides the team used by team
 * modes. Example:
 * <pre>{@code
 * BvcServerApi voice = BvcServer.get();
 * voice.createRoom("arena-1", ZoneSettings.of(VoiceMode.TEAM));
 * for (Player p : arenaPlayers) {
 *     voice.setRoom(p.getUniqueId(), "arena-1");
 *     voice.setGroup(p.getUniqueId(), teamOf(p));
 * }
 * // When someone dies: they can listen to their team but no longer talk.
 * voice.setCanSpeak(dead.getUniqueId(), false);
 * }</pre>
 * Methods may be called from any thread.
 */
public interface BvcServerApi {

    /** The sub-server's default zone from the config. */
    ZoneSettings defaultZone();

    /** Changes the default zone at runtime (not saved to the config). */
    void setDefaultZone(ZoneSettings settings);

    /** Creates or updates a room. */
    void createRoom(String name, ZoneSettings settings);

    Optional<ZoneSettings> room(String name);

    Set<String> rooms();

    /** Deletes a room; its players go back to the default zone. */
    void deleteRoom(String name);

    /** Puts a player in a room, or back into the default zone with {@code null}. */
    void setRoom(UUID player, String room);

    Optional<String> roomOf(UUID player);

    /** Online players currently put in {@code room} through {@link #setRoom}. */
    Set<UUID> membersOf(String room);

    /** Overrides the team used by team modes, or restores the scoreboard team with {@code null}. */
    void setGroup(UUID player, String group);

    /** The group set through {@link #setGroup}, if any (scoreboard teams are not included). */
    Optional<String> groupOf(UUID player);

    /** Speaker priority 0-255 on this sub-server; the proxy uses the higher of this and its own. */
    void setPriority(UUID player, int priority);

    /** The priority set through {@link #setPriority}, 0 if none. */
    int priorityOf(UUID player);

    void setCanSpeak(UUID player, boolean canSpeak);

    void setCanHear(UUID player, boolean canHear);

    /** Lets the player broadcast to the whole room with the broadcast key. */
    void setCanBroadcast(UUID player, boolean canBroadcast);

    /** Removes all per-player overrides (room, group, priority, flags). They also go when the player leaves. */
    void reset(UUID player);

    /** Whether the player has the client mod connected. */
    boolean hasVoice(UUID player);

    /**
     * Mutes through the proxy's mute system. {@code durationMillis} 0 = permanent.
     * {@code scope} {@code null} = this sub-server.
     */
    void mute(UUID player, MuteScope scope, long durationMillis, String reason, String operator);

    /** {@code scope} {@code null} = this sub-server. */
    void unmute(UUID player, MuteScope scope, String operator);

    void addListener(VoiceStateListener listener);

    void removeListener(VoiceStateListener listener);
}
