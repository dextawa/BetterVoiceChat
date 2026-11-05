package top.dext.bvc.protocol.packet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.data.Activation;
import top.dext.bvc.protocol.data.AudioCategory;
import top.dext.bvc.protocol.data.MuteKind;
import top.dext.bvc.protocol.data.Transport;
import top.dext.bvc.protocol.data.TransportPolicy;
import top.dext.bvc.protocol.data.VoiceMode;
import top.dext.bvc.protocol.io.Buf;

/**
 * Packets sent by the proxy to the client mod.
 */
public final class ProxyToClient {

    public interface Handler {

        void handle(Welcome packet);

        void handle(AuthResult packet);

        void handle(TransportAck packet);

        void handle(VoiceState packet);

        void handle(VoiceAudio packet);

        void handle(VoiceEnd packet);

        void handle(PlayerInfo packet);

        void handle(PlayerRemove packet);

        void handle(MuteStatus packet);

        void handle(Pong packet);

        void handle(Notice packet);
    }

    public static final PacketRegistry<Handler> REGISTRY = new PacketRegistry<Handler>("proxy->client")
            .register(0, Welcome.class, Welcome::read)
            .register(1, AuthResult.class, AuthResult::read)
            .register(2, TransportAck.class, TransportAck::read)
            .register(3, VoiceState.class, VoiceState::read)
            .register(4, VoiceAudio.class, VoiceAudio::read)
            .register(5, VoiceEnd.class, VoiceEnd::read)
            .register(6, PlayerInfo.class, PlayerInfo::read)
            .register(7, PlayerRemove.class, PlayerRemove::read)
            .register(8, MuteStatus.class, MuteStatus::read)
            .register(9, Pong.class, Pong::read)
            .register(10, Notice.class, Notice::read);

    private ProxyToClient() {
    }

    /** Reply to {@link ClientToProxy.Hello}: session id, one-time token and transport rules. */
    public static final class Welcome implements Packet<Handler> {
        public final int protocolVersion;
        /** The client's own speaker id. */
        public final int sessionId;
        public final byte[] token;
        public final TransportPolicy transportPolicy;
        /** Host for the stream transport; empty means "the address used for Minecraft". */
        public final String streamHost;
        /** Port for the stream transport; 0 means "the Minecraft port". */
        public final int streamPort;
        public final int connectTimeoutMillis;

        public Welcome(int protocolVersion, int sessionId, byte[] token, TransportPolicy transportPolicy,
                       String streamHost, int streamPort, int connectTimeoutMillis) {
            this.protocolVersion = protocolVersion;
            this.sessionId = sessionId;
            this.token = token;
            this.transportPolicy = transportPolicy;
            this.streamHost = streamHost;
            this.streamPort = streamPort;
            this.connectTimeoutMillis = connectTimeoutMillis;
        }

        static Welcome read(Buf buf) {
            return new Welcome(buf.readVarInt(), buf.readVarInt(), buf.readByteArray(64),
                    TransportPolicy.byId(buf.readUnsignedByte()), buf.readString(255), buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public void write(Buf buf) {
            buf.writeVarInt(protocolVersion).writeVarInt(sessionId).writeByteArray(token)
                    .writeByte(transportPolicy.id()).writeString(streamHost).writeVarInt(streamPort)
                    .writeVarInt(connectTimeoutMillis);
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }

    /** Answer to {@link ClientToProxy.Auth} on a stream connection; encryption starts after it. */
    public static final class AuthResult implements Packet<Handler> {
        public final boolean success;
        public final byte[] publicKey;
        public final String message;

        public AuthResult(boolean success, byte[] publicKey, String message) {
            this.success = success;
            this.publicKey = publicKey;
            this.message = message;
        }

        static AuthResult read(Buf buf) {
            return new AuthResult(buf.readBoolean(), buf.readByteArray(512), buf.readString(1024));
        }

        @Override
        public void write(Buf buf) {
            buf.writeBoolean(success).writeByteArray(publicKey).writeString(message);
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }

    /** The proxy now sends audio over this transport. */
    public static final class TransportAck implements Packet<Handler> {
        public final Transport transport;

        public TransportAck(Transport transport) {
            this.transport = transport;
        }

        static TransportAck read(Buf buf) {
            return new TransportAck(Transport.byId(buf.readUnsignedByte()));
        }

        @Override
        public void write(Buf buf) {
            buf.writeByte(transport.id());
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }

    /** Server-decided voice rules for where the player currently is. Drives the HUD and the encoder. */
    public static final class VoiceState implements Packet<Handler> {
        public final String server;
        public final String room;
        public final VoiceMode mode;
        public final Activation activation;
        public final float radius;
        public final int bitrate;
        /** How much lower-priority speakers are attenuated while a higher one talks, in dB (positive). */
        public final float duckingDecibels;
        public final int priority;
        public final boolean canSpeak;
        public final boolean canBroadcast;

        public VoiceState(String server, String room, VoiceMode mode, Activation activation, float radius, int bitrate,
                          float duckingDecibels, int priority, boolean canSpeak, boolean canBroadcast) {
            this.server = server;
            this.room = room;
            this.mode = mode;
            this.activation = activation;
            this.radius = radius;
            this.bitrate = bitrate;
            this.duckingDecibels = duckingDecibels;
            this.priority = priority;
            this.canSpeak = canSpeak;
            this.canBroadcast = canBroadcast;
        }

        static VoiceState read(Buf buf) {
            String server = buf.readString(128);
            String room = buf.readString(128);
            VoiceMode mode = VoiceMode.byId(buf.readUnsignedByte());
            Activation activation = Activation.byId(buf.readUnsignedByte());
            float radius = buf.readFloat();
            int bitrate = buf.readVarInt();
            float ducking = buf.readFloat();
            int priority = buf.readUnsignedByte();
            int flags = buf.readUnsignedByte();
            return new VoiceState(server, room, mode, activation, radius, bitrate, ducking, priority,
                    (flags & 1) != 0, (flags & 2) != 0);
        }

        @Override
        public void write(Buf buf) {
            buf.writeString(server).writeString(room).writeByte(mode.id()).writeByte(activation.id())
                    .writeFloat(radius).writeVarInt(bitrate).writeFloat(duckingDecibels).writeByte(priority)
                    .writeByte((canSpeak ? 1 : 0) | (canBroadcast ? 2 : 0));
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }

    /** One Opus frame from another player. */
    public static final class VoiceAudio implements Packet<Handler> {
        public final int speakerId;
        public final int sequence;
        public final AudioCategory category;
        public final int priority;
        public final byte[] opus;

        public VoiceAudio(int speakerId, int sequence, AudioCategory category, int priority, byte[] opus) {
            this.speakerId = speakerId;
            this.sequence = sequence;
            this.category = category;
            this.priority = priority;
            this.opus = opus;
        }

        static VoiceAudio read(Buf buf) {
            return new VoiceAudio(buf.readVarInt(), buf.readVarInt(), AudioCategory.byId(buf.readUnsignedByte()),
                    buf.readUnsignedByte(), buf.readByteArray(BvcProtocol.MAX_OPUS_PACKET));
        }

        @Override
        public void write(Buf buf) {
            buf.writeVarInt(speakerId).writeVarInt(sequence).writeByte(category.id()).writeByte(priority)
                    .writeByteArray(opus);
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }

    /** A speaker stopped talking; the client can drain its jitter buffer. */
    public static final class VoiceEnd implements Packet<Handler> {
        public final int speakerId;
        public final int sequence;

        public VoiceEnd(int speakerId, int sequence) {
            this.speakerId = speakerId;
            this.sequence = sequence;
        }

        static VoiceEnd read(Buf buf) {
            return new VoiceEnd(buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public void write(Buf buf) {
            buf.writeVarInt(speakerId).writeVarInt(sequence);
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }

    /** Maps speaker ids to players, so audio frames can stay small. */
    public static final class PlayerInfo implements Packet<Handler> {
        public final List<Entry> entries;

        public PlayerInfo(List<Entry> entries) {
            this.entries = Collections.unmodifiableList(entries);
        }

        static PlayerInfo read(Buf buf) {
            int count = buf.readCount(4096);
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entries.add(new Entry(buf.readVarInt(), buf.readUuid(), buf.readString(64)));
            }
            return new PlayerInfo(entries);
        }

        @Override
        public void write(Buf buf) {
            buf.writeVarInt(entries.size());
            for (Entry entry : entries) {
                buf.writeVarInt(entry.speakerId).writeUuid(entry.uuid).writeString(entry.name);
            }
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }

        public static final class Entry {
            public final int speakerId;
            public final UUID uuid;
            public final String name;

            public Entry(int speakerId, UUID uuid, String name) {
                this.speakerId = speakerId;
                this.uuid = uuid;
                this.name = name;
            }
        }
    }

    public static final class PlayerRemove implements Packet<Handler> {
        public final int[] speakerIds;

        public PlayerRemove(int[] speakerIds) {
            this.speakerIds = speakerIds;
        }

        static PlayerRemove read(Buf buf) {
            return new PlayerRemove(buf.readVarIntArray(4096));
        }

        @Override
        public void write(Buf buf) {
            buf.writeVarIntArray(speakerIds);
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }

    /** Whether the player is currently muted, and why. The client stops sending while muted. */
    public static final class MuteStatus implements Packet<Handler> {
        public final boolean muted;
        public final MuteKind kind;
        /** {@code *} for network-wide, otherwise the sub-server name. */
        public final String scope;
        public final String reason;
        /** Epoch millis when the mute ends; 0 for permanent. */
        public final long expiresAt;

        public MuteStatus(boolean muted, MuteKind kind, String scope, String reason, long expiresAt) {
            this.muted = muted;
            this.kind = kind;
            this.scope = scope;
            this.reason = reason;
            this.expiresAt = expiresAt;
        }

        public static MuteStatus unmuted() {
            return new MuteStatus(false, MuteKind.PLAYER, "", "", 0L);
        }

        static MuteStatus read(Buf buf) {
            return new MuteStatus(buf.readBoolean(), MuteKind.byId(buf.readUnsignedByte()), buf.readString(128),
                    buf.readString(1024), buf.readLong());
        }

        @Override
        public void write(Buf buf) {
            buf.writeBoolean(muted).writeByte(kind.id()).writeString(scope).writeString(reason).writeLong(expiresAt);
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }

    public static final class Pong implements Packet<Handler> {
        public final long id;

        public Pong(long id) {
            this.id = id;
        }

        static Pong read(Buf buf) {
            return new Pong(buf.readLong());
        }

        @Override
        public void write(Buf buf) {
            buf.writeLong(id);
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }

    /** Human-readable message for the player (protocol mismatch, auth failure...). */
    public static final class Notice implements Packet<Handler> {
        public final String message;

        public Notice(String message) {
            this.message = message;
        }

        static Notice read(Buf buf) {
            return new Notice(buf.readString(4096));
        }

        @Override
        public void write(Buf buf) {
            buf.writeString(message);
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }
}
