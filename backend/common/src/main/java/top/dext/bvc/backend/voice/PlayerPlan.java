package top.dext.bvc.backend.voice;

import java.util.Arrays;
import java.util.UUID;

import top.dext.bvc.api.server.ZoneSettings;

/**
 * What the planner decided for one player. Immutable; also used for {@code /bvcs info}.
 */
public final class PlayerPlan {

    public final int id;
    public final UUID uuid;
    /** Display name of the zone: room name, world name, or empty for the sub-server default. */
    public final String zoneName;
    public final ZoneSettings zone;
    public final int roomId;
    public final int groupId;
    /** Human readable group, for debugging; {@code null} when not in a group. */
    public final String groupKey;
    /** Sorted ids of players in range who hear this player. */
    public final int[] neighbors;
    public final int priority;
    public final int flags;

    PlayerPlan(int id, UUID uuid, String zoneName, ZoneSettings zone, int roomId, int groupId, String groupKey,
               int[] neighbors, int priority, int flags) {
        this.id = id;
        this.uuid = uuid;
        this.zoneName = zoneName;
        this.zone = zone;
        this.roomId = roomId;
        this.groupId = groupId;
        this.groupKey = groupKey;
        this.neighbors = neighbors;
        this.priority = priority;
        this.flags = flags;
    }

    PlayerPlan withNeighbors(int[] value) {
        return new PlayerPlan(id, uuid, zoneName, zone, roomId, groupId, groupKey, value, priority, flags);
    }

    boolean sameMembership(PlayerPlan other) {
        return roomId == other.roomId && groupId == other.groupId;
    }

    boolean sameNeighbors(PlayerPlan other) {
        return Arrays.equals(neighbors, other.neighbors);
    }

    boolean sameFlags(PlayerPlan other) {
        return priority == other.priority && flags == other.flags;
    }

    boolean sameZone(PlayerPlan other) {
        return zoneName.equals(other.zoneName) && zone.mode() == other.zone.mode()
                && zone.activation() == other.zone.activation() && (float) zone.radius() == (float) other.zone.radius();
    }
}
