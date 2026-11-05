package top.dext.bvc.backend.voice;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import top.dext.bvc.protocol.data.Activation;
import top.dext.bvc.protocol.data.VoiceMode;
import top.dext.bvc.protocol.packet.BackendToProxy;
import top.dext.bvc.protocol.packet.Packet;

/**
 * Turns plan changes into {@code bvc:server} packets: only entries that changed, unless a full
 * resend is requested, split so no payload exceeds what Bukkit 1.8 lets a plugin send (32 KB).
 */
public final class TableEmitter {

    /** Keeps payloads under Bukkit 1.8's 32766-byte plugin message limit with room to spare. */
    static final int BUDGET = 30_000;

    private long sequence;

    /**
     * @param startSequence first sequence number; use a time-based value so a restarted backend
     *                      never goes below what the proxy saw from the previous instance
     */
    public TableEmitter(long startSequence) {
        this.sequence = startSequence;
    }

    public List<Packet<BackendToProxy.Handler>> diff(Map<Integer, PlayerPlan> previous, Map<Integer, PlayerPlan> current,
                                                     boolean full) {
        List<BackendToProxy.Membership.Entry> membership = new ArrayList<BackendToProxy.Membership.Entry>();
        List<BackendToProxy.PlayerFlags.Entry> flags = new ArrayList<BackendToProxy.PlayerFlags.Entry>();
        List<BackendToProxy.ZoneInfo.Entry> zones = new ArrayList<BackendToProxy.ZoneInfo.Entry>();
        List<BackendToProxy.Neighbors.Entry> neighbors = new ArrayList<BackendToProxy.Neighbors.Entry>();

        for (PlayerPlan plan : current.values()) {
            PlayerPlan old = full ? null : previous.get(plan.id);
            if (old == null || !old.sameMembership(plan)) {
                membership.add(new BackendToProxy.Membership.Entry(plan.id, plan.roomId, plan.groupId));
            }
            if (old == null || !old.sameFlags(plan)) {
                flags.add(new BackendToProxy.PlayerFlags.Entry(plan.id, plan.priority, plan.flags));
            }
            if (old == null || !old.sameZone(plan)) {
                zones.add(new BackendToProxy.ZoneInfo.Entry(plan.id, plan.zoneName, toProtocol(plan.zone.mode()),
                        toProtocol(plan.zone.activation()), (float) plan.zone.radius()));
            }
            if (old == null ? (full || plan.neighbors.length > 0) : !old.sameNeighbors(plan)) {
                neighbors.add(new BackendToProxy.Neighbors.Entry(plan.id, plan.neighbors));
            }
        }

        if (membership.isEmpty() && flags.isEmpty() && zones.isEmpty() && neighbors.isEmpty()) {
            return Collections.emptyList();
        }
        List<Packet<BackendToProxy.Handler>> packets = new ArrayList<Packet<BackendToProxy.Handler>>();
        for (List<BackendToProxy.Membership.Entry> chunk : split(membership, 15)) {
            packets.add(new BackendToProxy.Membership(sequence++, chunk));
        }
        for (List<BackendToProxy.PlayerFlags.Entry> chunk : split(flags, 11)) {
            packets.add(new BackendToProxy.PlayerFlags(sequence++, chunk));
        }
        for (List<BackendToProxy.ZoneInfo.Entry> chunk : splitZones(zones)) {
            packets.add(new BackendToProxy.ZoneInfo(sequence++, chunk));
        }
        for (List<BackendToProxy.Neighbors.Entry> chunk : splitNeighbors(neighbors)) {
            packets.add(new BackendToProxy.Neighbors(sequence++, chunk));
        }
        return packets;
    }

    // The API and the wire protocol each have their own copy of these enums (the API must not
    // depend on the protocol), hence the fully qualified names.

    static Activation toProtocol(top.dext.bvc.api.server.Activation activation) {
        return activation == top.dext.bvc.api.server.Activation.PTT_ONLY ? Activation.PTT_ONLY : Activation.ANY;
    }

    static VoiceMode toProtocol(top.dext.bvc.api.server.VoiceMode mode) {
        switch (mode) {
            case PROXIMITY:
                return VoiceMode.PROXIMITY;
            case TEAM:
                return VoiceMode.TEAM;
            case FREE:
                return VoiceMode.FREE;
            case TEAM_PROXIMITY:
                return VoiceMode.TEAM_PROXIMITY;
            default:
                return VoiceMode.DISABLED;
        }
    }

    /** Fixed-size entries: at most {@code BUDGET / entrySize} per packet. */
    private static <E> List<List<E>> split(List<E> entries, int entrySize) {
        int perPacket = Math.min(BackendToProxy.MAX_ENTRIES, BUDGET / entrySize);
        List<List<E>> chunks = new ArrayList<List<E>>();
        for (int i = 0; i < entries.size(); i += perPacket) {
            chunks.add(new ArrayList<E>(entries.subList(i, Math.min(entries.size(), i + perPacket))));
        }
        return chunks;
    }

    private static List<List<BackendToProxy.ZoneInfo.Entry>> splitZones(List<BackendToProxy.ZoneInfo.Entry> entries) {
        List<List<BackendToProxy.ZoneInfo.Entry>> chunks = new ArrayList<List<BackendToProxy.ZoneInfo.Entry>>();
        List<BackendToProxy.ZoneInfo.Entry> chunk = new ArrayList<BackendToProxy.ZoneInfo.Entry>();
        int size = 0;
        for (BackendToProxy.ZoneInfo.Entry entry : entries) {
            int entrySize = 5 + 3 + entry.room.getBytes(StandardCharsets.UTF_8).length + 2 + 4;
            if (!chunk.isEmpty() && (size + entrySize > BUDGET || chunk.size() == BackendToProxy.MAX_ENTRIES)) {
                chunks.add(chunk);
                chunk = new ArrayList<BackendToProxy.ZoneInfo.Entry>();
                size = 0;
            }
            chunk.add(entry);
            size += entrySize;
        }
        if (!chunk.isEmpty()) {
            chunks.add(chunk);
        }
        return chunks;
    }

    private static List<List<BackendToProxy.Neighbors.Entry>> splitNeighbors(List<BackendToProxy.Neighbors.Entry> entries) {
        List<List<BackendToProxy.Neighbors.Entry>> chunks = new ArrayList<List<BackendToProxy.Neighbors.Entry>>();
        List<BackendToProxy.Neighbors.Entry> chunk = new ArrayList<BackendToProxy.Neighbors.Entry>();
        int size = 0;
        for (BackendToProxy.Neighbors.Entry entry : entries) {
            int entrySize = 10 + 5 * entry.listenerIds.length;
            if (!chunk.isEmpty() && (size + entrySize > BUDGET || chunk.size() == BackendToProxy.MAX_ENTRIES)) {
                chunks.add(chunk);
                chunk = new ArrayList<BackendToProxy.Neighbors.Entry>();
                size = 0;
            }
            chunk.add(entry);
            size += entrySize;
        }
        if (!chunk.isEmpty()) {
            chunks.add(chunk);
        }
        return chunks;
    }
}
