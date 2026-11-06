package top.dext.bvc.backend.voice;

import java.util.UUID;

/**
 * Where a player is and what the sub-server knows about them, captured on the thread that owns
 * the player. Immutable.
 */
public final class PlayerSnapshot {

    /** Compact id from the proxy; 0 until the engine fills it in. */
    public final int id;
    public final UUID uuid;
    public final String world;
    public final double x;
    public final double y;
    public final double z;
    public final boolean spectator;
    /** Scoreboard team name, or {@code null}. */
    public final String team;
    /** Highest {@code bvc.priority.<n>} the player has on this sub-server. */
    public final int permissionPriority;
    /** Whether the player has {@code bvc.broadcast} on this sub-server. */
    public final boolean permissionBroadcast;

    public PlayerSnapshot(int id, UUID uuid, String world, double x, double y, double z, boolean spectator, String team,
                          int permissionPriority, boolean permissionBroadcast) {
        this.id = id;
        this.uuid = uuid;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.spectator = spectator;
        this.team = team;
        this.permissionPriority = permissionPriority;
        this.permissionBroadcast = permissionBroadcast;
    }

    public PlayerSnapshot withId(int newId) {
        return new PlayerSnapshot(newId, uuid, world, x, y, z, spectator, team, permissionPriority, permissionBroadcast);
    }

    double distanceSquared(PlayerSnapshot other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
