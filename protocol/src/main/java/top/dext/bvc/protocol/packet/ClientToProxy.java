package top.dext.bvc.protocol.packet;

import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.data.Transport;
import top.dext.bvc.protocol.io.Buf;

/**
 * Packets sent by the client mod to the proxy, over {@code bvc:voice} or the stream transport.
 */
public final class ClientToProxy {

    public interface Handler {

        void handle(Hello packet);

        void handle(Auth packet);

        void handle(SelectTransport packet);

        void handle(MicAudio packet);

        void handle(MicEnd packet);

        void handle(ClientStatus packet);

        void handle(Ping packet);
    }

    public static final PacketRegistry<Handler> REGISTRY = new PacketRegistry<Handler>("client->proxy")
            .register(0, Hello.class, Hello::read)
            .register(1, Auth.class, Auth::read)
            .register(2, SelectTransport.class, SelectTransport::read)
            .register(3, MicAudio.class, MicAudio::read)
            .register(4, MicEnd.class, MicEnd::read)
            .register(5, ClientStatus.class, ClientStatus::read)
            .register(6, Ping.class, Ping::read);

    private ClientToProxy() {
    }

    /** First packet, always over {@code bvc:voice}. Announces the mod. */
    public static final class Hello implements Packet<Handler> {
        public final int protocolVersion;
        public final String modVersion;
        public final String minecraftVersion;
        public final String loader;
        /** Bitmask of {@link Transport#bit()}. */
        public final int transports;

        public Hello(int protocolVersion, String modVersion, String minecraftVersion, String loader, int transports) {
            this.protocolVersion = protocolVersion;
            this.modVersion = modVersion;
            this.minecraftVersion = minecraftVersion;
            this.loader = loader;
            this.transports = transports;
        }

        static Hello read(Buf buf) {
            return new Hello(buf.readVarInt(), buf.readString(64), buf.readString(32), buf.readString(32), buf.readVarInt());
        }

        @Override
        public void write(Buf buf) {
            buf.writeVarInt(protocolVersion).writeString(modVersion).writeString(minecraftVersion)
                    .writeString(loader).writeVarInt(transports);
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }

    /**
     * First packet on a stream connection, right after the fake handshake. Proves knowledge of
     * the session token without sending it: {@code mac = HMAC(token, publicKey)}.
     */
    public static final class Auth implements Packet<Handler> {
        public final int sessionId;
        public final byte[] publicKey;
        public final byte[] mac;

        public Auth(int sessionId, byte[] publicKey, byte[] mac) {
            this.sessionId = sessionId;
            this.publicKey = publicKey;
            this.mac = mac;
        }

        static Auth read(Buf buf) {
            return new Auth(buf.readVarInt(), buf.readByteArray(512), buf.readByteArray(64));
        }

        @Override
        public void write(Buf buf) {
            buf.writeVarInt(sessionId).writeByteArray(publicKey).writeByteArray(mac);
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }

    /** Tells the proxy which transport the client settled on (sent over {@code bvc:voice}). */
    public static final class SelectTransport implements Packet<Handler> {
        public final Transport transport;

        public SelectTransport(Transport transport) {
            this.transport = transport;
        }

        static SelectTransport read(Buf buf) {
            return new SelectTransport(Transport.byId(buf.readUnsignedByte()));
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

    /** One 20 ms Opus frame from the local microphone. */
    public static final class MicAudio implements Packet<Handler> {
        /** The speaker holds the broadcast key (only honoured with broadcast permission). */
        public static final int FLAG_BROADCAST = 1;

        public final int sequence;
        public final int flags;
        public final byte[] opus;

        public MicAudio(int sequence, int flags, byte[] opus) {
            this.sequence = sequence;
            this.flags = flags;
            this.opus = opus;
        }

        public boolean isBroadcast() {
            return (flags & FLAG_BROADCAST) != 0;
        }

        static MicAudio read(Buf buf) {
            return new MicAudio(buf.readVarInt(), buf.readUnsignedByte(), buf.readByteArray(BvcProtocol.MAX_OPUS_PACKET));
        }

        @Override
        public void write(Buf buf) {
            buf.writeVarInt(sequence).writeByte(flags).writeByteArray(opus);
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }

    /** The player released push-to-talk or voice activation went quiet. */
    public static final class MicEnd implements Packet<Handler> {
        public final int sequence;

        public MicEnd(int sequence) {
            this.sequence = sequence;
        }

        static MicEnd read(Buf buf) {
            return new MicEnd(buf.readVarInt());
        }

        @Override
        public void write(Buf buf) {
            buf.writeVarInt(sequence);
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }

    /** Local mic/deafen state, so the proxy can skip sending audio to deafened players. */
    public static final class ClientStatus implements Packet<Handler> {
        public final boolean microphoneMuted;
        public final boolean deafened;

        public ClientStatus(boolean microphoneMuted, boolean deafened) {
            this.microphoneMuted = microphoneMuted;
            this.deafened = deafened;
        }

        static ClientStatus read(Buf buf) {
            int flags = buf.readUnsignedByte();
            return new ClientStatus((flags & 1) != 0, (flags & 2) != 0);
        }

        @Override
        public void write(Buf buf) {
            buf.writeByte((microphoneMuted ? 1 : 0) | (deafened ? 2 : 0));
        }

        @Override
        public void handle(Handler handler) {
            handler.handle(this);
        }
    }

    public static final class Ping implements Packet<Handler> {
        public final long id;

        public Ping(long id) {
            this.id = id;
        }

        static Ping read(Buf buf) {
            return new Ping(buf.readLong());
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
}
