package top.dext.bvc.protocol.packet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import top.dext.bvc.protocol.data.Activation;
import top.dext.bvc.protocol.data.VoiceMode;
import top.dext.bvc.protocol.io.Buf;

/**
 * Packets sent by the sub-server plugin to the proxy over {@code bvc:server}.
 * <p>
 * The sub-server decides who can hear whom; these packets carry its decisions. Player ids are
 * the compact ids the proxy handed out with {@link ProxyToBackend.BindPlayers}. Every entry
 * fully replaces the previous state for that player, so lost or repeated messages heal on the
 * next periodic resend.
 */
public final class BackendToProxy {

    public interface Handler {

        void handle(BackendHello packet);

        void handle(Membership packet);

        void handle(Neighbors packet);

        void handle(PlayerFlags packet);

        void handle(ZoneInfo packet);

        void handle(MuteRequest packet);
    }

    public static final PacketRegistry<Handler> REGISTRY = new PacketRegistry<Handler>("backend->proxy")
            .register(0, BackendHello.class, BackendHello::read)
            .register(1, Membership.class, Membership::read)
            .register(2, Neighbors.class, Neighbors::read)
            .register(3, PlayerFlags.class, PlayerFlags::read)
            .register(4, ZoneInfo.class, ZoneInfo::read)
            .register(5, MuteRequest.class, MuteRequest::read);

    /** Upper bound on entries in one packet; senders split larger tables. */
    public static final int MAX_ENTRIES = 8192;

    private BackendToProxy() {
    }

    /**
     * Answer to a {@link ProxyToBackend.ProxyHello} from a proxy instance the backend has not seen
     * yet. {@code instanceId} is random per backend start: when it changes, the proxy drops the
     * tables it had for this sub-server (the backend restarted and starts from scratch).
     */
    public static final class BackendHello implements Packet<Handler> {
        public final int protocolVersion;
        public final String pluginVersion;
        public final String software;
        public final long instanceId;
        /** {@link top.dext.bvc.protocol.crypto.BvcCrypto#linkProof} when a shared secret is set, else empty. */
        public final byte[] proof;

        public BackendHello(int protocolVersion, String pluginVersion, String software, long instanceId) {
            this(protocolVersion, pluginVersion, software, instanceId, new byte[0]);
        }

        public BackendHello(int protocolVersion, String pluginVersion, String software, long instanceId, byte[] proof) {
            this.protocolVersion = protocolVersion;
            this.pluginVersion = pluginVersion;
            this.software = software;
            this.instanceId = instanceId;
            this.proof = proof;
        }

        static BackendHello read(Buf buf) {
            BackendHello base = new BackendHello(buf.readVarInt(), buf.readString(64), buf.readString(256), buf.readLong());
            return buf.isReadable()
                    ? new BackendHello(base.protocolVersion, base.pluginVersion, base.software, base.instanceId, buf.readByteArray(64))
                    : base;
        }

        @Override
        public void write(Buf buf) {
            buf.writeVarInt(protocolVersion).writeString(pluginVersion).writeString(software).writeLong(instanceId)
                    .writeByteArray(proof);
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }

    /**
     * Room and group of each player. Room = isolation boundary and broadcast target (the whole
     * sub-server unless a minigame creates rooms). Group = everyone who hears each other at any
     * distance: the room itself in free mode, the team in team mode, 0 for none.
     */
    public static final class Membership implements Packet<Handler> {
        /** Per-backend counter; the proxy ignores entries older than what it already applied. */
        public final long sequence;
        public final List<Entry> entries;

        public Membership(long sequence, List<Entry> entries) {
            this.sequence = sequence;
            this.entries = Collections.unmodifiableList(entries);
        }

        static Membership read(Buf buf) {
            long sequence = buf.readVarLong();
            int count = buf.readCount(MAX_ENTRIES);
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entries.add(new Entry(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
            }
            return new Membership(sequence, entries);
        }

        @Override
        public void write(Buf buf) {
            buf.writeVarLong(sequence).writeVarInt(entries.size());
            for (Entry entry : entries) {
                buf.writeVarInt(entry.playerId).writeVarInt(entry.roomId).writeVarInt(entry.groupId);
            }
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }

        public static final class Entry {
            public final int playerId;
            public final int roomId;
            public final int groupId;

            public Entry(int playerId, int roomId, int groupId) {
                this.playerId = playerId;
                this.roomId = roomId;
                this.groupId = groupId;
            }
        }
    }

    /** For each speaker, the full list of players in range who can hear them. */
    public static final class Neighbors implements Packet<Handler> {
        /** Per-backend counter; the proxy ignores entries older than what it already applied. */
        public final long sequence;
        public final List<Entry> entries;

        public Neighbors(long sequence, List<Entry> entries) {
            this.sequence = sequence;
            this.entries = Collections.unmodifiableList(entries);
        }

        static Neighbors read(Buf buf) {
            long sequence = buf.readVarLong();
            int count = buf.readCount(MAX_ENTRIES);
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entries.add(new Entry(buf.readVarInt(), buf.readVarIntArray(MAX_ENTRIES)));
            }
            return new Neighbors(sequence, entries);
        }

        @Override
        public void write(Buf buf) {
            buf.writeVarLong(sequence).writeVarInt(entries.size());
            for (Entry entry : entries) {
                buf.writeVarInt(entry.speakerId).writeVarIntArray(entry.listenerIds);
            }
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }

        public static final class Entry {
            public final int speakerId;
            public final int[] listenerIds;

            public Entry(int speakerId, int[] listenerIds) {
                this.speakerId = speakerId;
                this.listenerIds = listenerIds;
            }
        }
    }

    /** Per-player permissions decided by the sub-server (spectators, dead players, commanders). */
    public static final class PlayerFlags implements Packet<Handler> {
        public static final int CAN_SPEAK = 1;
        public static final int CAN_HEAR = 2;
        public static final int CAN_BROADCAST = 4;
        public static final int DEFAULT = CAN_SPEAK | CAN_HEAR;

        /** Per-backend counter; the proxy ignores entries older than what it already applied. */
        public final long sequence;
        public final List<Entry> entries;

        public PlayerFlags(long sequence, List<Entry> entries) {
            this.sequence = sequence;
            this.entries = Collections.unmodifiableList(entries);
        }

        static PlayerFlags read(Buf buf) {
            long sequence = buf.readVarLong();
            int count = buf.readCount(MAX_ENTRIES);
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entries.add(new Entry(buf.readVarInt(), buf.readUnsignedByte(), buf.readVarInt()));
            }
            return new PlayerFlags(sequence, entries);
        }

        @Override
        public void write(Buf buf) {
            buf.writeVarLong(sequence).writeVarInt(entries.size());
            for (Entry entry : entries) {
                buf.writeVarInt(entry.playerId).writeByte(entry.priority).writeVarInt(entry.flags);
            }
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }

        public static final class Entry {
            public final int playerId;
            public final int priority;
            public final int flags;

            public Entry(int playerId, int priority, int flags) {
                this.playerId = playerId;
                this.priority = priority;
                this.flags = flags;
            }
        }
    }

    /** The zone rules a player is under; the proxy relays them to the client for display. */
    public static final class ZoneInfo implements Packet<Handler> {
        /** Per-backend counter; the proxy ignores entries older than what it already applied. */
        public final long sequence;
        public final List<Entry> entries;

        public ZoneInfo(long sequence, List<Entry> entries) {
            this.sequence = sequence;
            this.entries = Collections.unmodifiableList(entries);
        }

        static ZoneInfo read(Buf buf) {
            long sequence = buf.readVarLong();
            int count = buf.readCount(MAX_ENTRIES);
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entries.add(new Entry(buf.readVarInt(), buf.readString(128), VoiceMode.byId(buf.readUnsignedByte()),
                        Activation.byId(buf.readUnsignedByte()), buf.readFloat()));
            }
            return new ZoneInfo(sequence, entries);
        }

        @Override
        public void write(Buf buf) {
            buf.writeVarLong(sequence).writeVarInt(entries.size());
            for (Entry entry : entries) {
                buf.writeVarInt(entry.playerId).writeString(entry.room).writeByte(entry.mode.id())
                        .writeByte(entry.activation.id()).writeFloat(entry.radius);
            }
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }

        public static final class Entry {
            public final int playerId;
            public final String room;
            public final VoiceMode mode;
            public final Activation activation;
            public final float radius;

            public Entry(int playerId, String room, VoiceMode mode, Activation activation, float radius) {
                this.playerId = playerId;
                this.room = room;
                this.mode = mode;
                this.activation = activation;
                this.radius = radius;
            }
        }
    }

    /** Lets sub-server plugins mute through the proxy's mute system. */
    public static final class MuteRequest implements Packet<Handler> {
        public final UUID target;
        /** {@code *} for network-wide, empty for "this sub-server", otherwise a server name. */
        public final String scope;
        /** Duration in millis; 0 for permanent, {@link #UNMUTE} to lift a mute instead. */
        public final long durationMillis;
        public final String reason;
        public final String operator;

        public static final long UNMUTE = Long.MIN_VALUE;

        public MuteRequest(UUID target, String scope, long durationMillis, String reason, String operator) {
            this.target = target;
            this.scope = scope;
            this.durationMillis = durationMillis;
            this.reason = reason;
            this.operator = operator;
        }

        public boolean isUnmute() {
            return durationMillis == UNMUTE;
        }

        static MuteRequest read(Buf buf) {
            return new MuteRequest(buf.readUuid(), buf.readString(128), buf.readLong(), buf.readString(1024),
                    buf.readString(64));
        }

        @Override
        public void write(Buf buf) {
            buf.writeUuid(target).writeString(scope).writeLong(durationMillis).writeString(reason).writeString(operator);
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }
}
