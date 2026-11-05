package top.dext.bvc.proxy.routing;

import top.dext.bvc.protocol.data.Activation;
import top.dext.bvc.protocol.data.VoiceMode;
import top.dext.bvc.protocol.packet.BackendToProxy.PlayerFlags;

/**
 * What the sub-server decided about one player. Immutable; replaced as a whole on updates so
 * the router can read it without locks. Each table keeps its own sequence number so stale,
 * reordered backend messages are ignored.
 */
public record PlayerRoute(
        int roomId,
        int groupId,
        int[] neighbors,
        int priority,
        int flags,
        String room,
        VoiceMode mode,
        Activation activation,
        float radius,
        long membershipSequence,
        long neighborsSequence,
        long flagsSequence,
        long zoneSequence) {

    private static final int[] NO_NEIGHBORS = new int[0];

    /** Before the backend has said anything: default room, no group, may speak and hear, no voice mode yet. */
    public static final PlayerRoute INITIAL = new PlayerRoute(0, 0, NO_NEIGHBORS, 0, PlayerFlags.DEFAULT, "",
            VoiceMode.DISABLED, Activation.ANY, 0f, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE);

    public boolean canSpeak() {
        return (flags & PlayerFlags.CAN_SPEAK) != 0;
    }

    public boolean canHear() {
        return (flags & PlayerFlags.CAN_HEAR) != 0;
    }

    public boolean canBroadcast() {
        return (flags & PlayerFlags.CAN_BROADCAST) != 0;
    }

    PlayerRoute withMembership(long sequence, int newRoom, int newGroup) {
        return new PlayerRoute(newRoom, newGroup, neighbors, priority, flags, room, mode, activation, radius,
                sequence, neighborsSequence, flagsSequence, zoneSequence);
    }

    PlayerRoute withNeighbors(long sequence, int[] newNeighbors) {
        return new PlayerRoute(roomId, groupId, newNeighbors.length == 0 ? NO_NEIGHBORS : newNeighbors, priority, flags,
                room, mode, activation, radius, membershipSequence, sequence, flagsSequence, zoneSequence);
    }

    PlayerRoute withFlags(long sequence, int newPriority, int newFlags) {
        return new PlayerRoute(roomId, groupId, neighbors, newPriority, newFlags, room, mode, activation, radius,
                membershipSequence, neighborsSequence, sequence, zoneSequence);
    }

    PlayerRoute withZone(long sequence, String newRoom, VoiceMode newMode, Activation newActivation, float newRadius) {
        return new PlayerRoute(roomId, groupId, neighbors, priority, flags, newRoom, newMode, newActivation, newRadius,
                membershipSequence, neighborsSequence, flagsSequence, sequence);
    }
}
