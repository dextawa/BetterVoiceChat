package top.dext.bvc.proxy.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import top.dext.bvc.protocol.crypto.BvcCrypto;
import top.dext.bvc.protocol.crypto.SessionKeys;
import top.dext.bvc.protocol.io.Buf;
import top.dext.bvc.protocol.packet.ClientToProxy;
import top.dext.bvc.protocol.packet.Packet;
import top.dext.bvc.protocol.packet.ProxyToClient;
import top.dext.bvc.protocol.stream.StreamHandshake;
import top.dext.bvc.proxy.FakePlayer;
import top.dext.bvc.proxy.TestLogger;
import top.dext.bvc.proxy.config.BvcConfig;
import top.dext.bvc.proxy.platform.BvcLogger;
import top.dext.bvc.proxy.session.SessionManager;
import top.dext.bvc.proxy.session.StreamConnection;
import top.dext.bvc.proxy.session.VoiceSession;

class StreamTransportTest {

    /** Stands in for the Minecraft handlers the platform installs. */
    private static final class MinecraftHandler extends ChannelInboundHandlerAdapter {
        final List<byte[]> received = new ArrayList<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            received.add(ByteBufUtil.getBytes(buf));
            buf.release();
        }
    }

    private static final class Gateway implements StreamGateway {
        final SessionManager sessions = new SessionManager();
        final List<Packet<ClientToProxy.Handler>> packets = new ArrayList<>();
        StreamConnection connection;
        boolean closed;
        BvcConfig config = BvcConfig.defaults();

        @Override
        public BvcConfig config() {
            return config;
        }

        @Override
        public BvcLogger logger() {
            return new TestLogger();
        }

        @Override
        public VoiceSession sessionForAuth(int sessionId) {
            return sessions.get(sessionId);
        }

        @Override
        public void onStreamAuthenticated(VoiceSession session, StreamConnection connection) {
            this.connection = connection;
            session.attachStream(connection);
        }

        @Override
        public void onStreamPacket(VoiceSession session, Packet<ClientToProxy.Handler> packet) {
            packets.add(packet);
        }

        @Override
        public void onStreamClosed(VoiceSession session, StreamConnection connection) {
            closed = true;
        }
    }

    private static EmbeddedChannel newConnection(MinecraftHandler minecraft, Gateway gateway, AtomicInteger pending) {
        EmbeddedChannel channel = new EmbeddedChannel(minecraft);
        StreamDetector.install(channel, gateway, pending);
        return channel;
    }

    @Test
    void normalMinecraftTrafficPassesThroughUntouched() {
        MinecraftHandler minecraft = new MinecraftHandler();
        Gateway gateway = new Gateway();
        EmbeddedChannel channel = newConnection(minecraft, gateway, new AtomicInteger());

        Buf body = new Buf().writeVarInt(0).writeVarInt(770).writeString("play.example.com").writeShort(25565).writeVarInt(2);
        Buf handshake = new Buf().writeVarInt(body.readableBytes());
        handshake.writeBytes(body.toByteArray());
        byte[] login = {5, 0, 3, 'B', 'o', 'b'};
        byte[] first = handshake.toByteArray();

        // Split across reads to exercise the "need more" path.
        channel.writeInbound(Unpooled.wrappedBuffer(first, 0, 3));
        assertTrue(minecraft.received.isEmpty());
        channel.writeInbound(Unpooled.wrappedBuffer(first, 3, first.length - 3));
        channel.writeInbound(Unpooled.wrappedBuffer(login));

        assertNull(channel.pipeline().get(StreamDetector.NAME), "detector removes itself");
        byte[] all = concat(minecraft.received);
        assertEquals(first.length + login.length, all.length);
        assertEquals(first[0], all[0]);
        assertEquals('b', all[all.length - 1]);
    }

    @Test
    void streamHandshakeAuthenticatesAndEncrypts() throws Exception {
        MinecraftHandler minecraft = new MinecraftHandler();
        Gateway gateway = new Gateway();
        AtomicInteger pending = new AtomicInteger();
        EmbeddedChannel channel = newConnection(minecraft, gateway, pending);

        VoiceSession session = gateway.sessions.create(new FakePlayer("Alice"), 0);
        session.setState(VoiceSession.State.HANDSHAKING);
        byte[] token = BvcCrypto.randomToken();
        session.issueToken(token, System.currentTimeMillis() + 30_000);

        KeyPair clientKeys = BvcCrypto.generateKeyPair();
        byte[] clientPublic = BvcCrypto.encodePublicKey(clientKeys.getPublic());
        byte[] auth = frame(ClientToProxy.REGISTRY.encode(new ClientToProxy.Auth(session.id(), clientPublic,
                BvcCrypto.authMac(token, clientPublic))));

        // Handshake and Auth in a single TCP read, like a real client would often produce.
        channel.writeInbound(Unpooled.wrappedBuffer(concat(List.of(StreamHandshake.encode("play.example.com", 25565, 47), auth))));

        assertTrue(minecraft.received.isEmpty(), "Minecraft handlers never see BVC bytes");
        assertNull(channel.pipeline().get(MinecraftHandler.class), "Minecraft handlers were removed");
        assertNotNull(gateway.connection);
        assertEquals(0, pending.get());

        ProxyToClient.AuthResult result = (ProxyToClient.AuthResult) ProxyToClient.REGISTRY.decode(unframe(readOutbound(channel)));
        assertTrue(result.success);
        SessionKeys keys = BvcCrypto.deriveSessionKeys(
                BvcCrypto.agree(clientKeys.getPrivate(), BvcCrypto.decodePublicKey(result.publicKey)), token);
        Cipher clientOut = keys.clientOutbound();
        Cipher clientIn = keys.clientInbound();

        // Client -> proxy, encrypted.
        byte[] mic = frame(ClientToProxy.REGISTRY.encode(new ClientToProxy.MicAudio(1, 0, new byte[] {9, 9, 9})));
        channel.writeInbound(Unpooled.wrappedBuffer(clientOut.update(mic)));
        assertEquals(1, gateway.packets.size());
        assertEquals(1, ((ClientToProxy.MicAudio) gateway.packets.get(0)).sequence);

        // Proxy -> client, batched and encrypted.
        session.send(new ProxyToClient.Pong(7));
        session.send(new ProxyToClient.Pong(8));
        channel.runPendingTasks();
        byte[] encrypted = readAllOutbound(channel);
        Buf plain = Buf.wrap(clientIn.update(encrypted));
        assertEquals(7L, ((ProxyToClient.Pong) ProxyToClient.REGISTRY.decode(Buf.wrap(plain.readBytes(plain.readVarInt())))).id);
        assertEquals(8L, ((ProxyToClient.Pong) ProxyToClient.REGISTRY.decode(Buf.wrap(plain.readBytes(plain.readVarInt())))).id);

        channel.close();
        assertTrue(gateway.closed);
    }

    @Test
    void wrongMacIsRejectedAndTokenIsSingleUse() throws Exception {
        Gateway gateway = new Gateway();
        AtomicInteger pending = new AtomicInteger();
        VoiceSession session = gateway.sessions.create(new FakePlayer("Alice"), 0);
        session.setState(VoiceSession.State.HANDSHAKING);
        byte[] token = BvcCrypto.randomToken();
        session.issueToken(token, System.currentTimeMillis() + 30_000);
        byte[] clientPublic = BvcCrypto.encodePublicKey(BvcCrypto.generateKeyPair().getPublic());

        EmbeddedChannel attacker = newConnection(new MinecraftHandler(), gateway, pending);
        attacker.writeInbound(Unpooled.wrappedBuffer(concat(List.of(StreamHandshake.encode("h", 25565, 47),
                frame(ClientToProxy.REGISTRY.encode(new ClientToProxy.Auth(session.id(), clientPublic, new byte[32])))))));
        ProxyToClient.AuthResult result = (ProxyToClient.AuthResult) ProxyToClient.REGISTRY.decode(unframe(readOutbound(attacker)));
        assertFalse(result.success);
        assertFalse(attacker.isActive());
        assertNull(gateway.connection);

        // The failed attempt consumed the token: even the right MAC no longer works.
        EmbeddedChannel late = newConnection(new MinecraftHandler(), gateway, pending);
        late.writeInbound(Unpooled.wrappedBuffer(concat(List.of(StreamHandshake.encode("h", 25565, 47),
                frame(ClientToProxy.REGISTRY.encode(new ClientToProxy.Auth(session.id(), clientPublic,
                        BvcCrypto.authMac(token, clientPublic))))))));
        assertFalse(((ProxyToClient.AuthResult) ProxyToClient.REGISTRY.decode(unframe(readOutbound(late)))).success);
        assertEquals(0, pending.get());
    }

    @Test
    void anythingButAuthFirstIsDropped() {
        Gateway gateway = new Gateway();
        EmbeddedChannel channel = newConnection(new MinecraftHandler(), gateway, new AtomicInteger());
        channel.writeInbound(Unpooled.wrappedBuffer(concat(List.of(StreamHandshake.encode("h", 25565, 47),
                frame(ClientToProxy.REGISTRY.encode(new ClientToProxy.Ping(1)))))));
        assertFalse(channel.isActive());
        assertTrue(gateway.packets.isEmpty());
    }

    @Test
    void pendingLimitClosesExcessConnections() {
        Gateway gateway = new Gateway();
        gateway.config = BvcConfig.parse("transport:\n  max-pending-connections: 1\n");
        AtomicInteger pending = new AtomicInteger();
        EmbeddedChannel first = newConnection(new MinecraftHandler(), gateway, pending);
        first.writeInbound(Unpooled.wrappedBuffer(StreamHandshake.encode("h", 25565, 47)));
        assertTrue(first.isActive());
        EmbeddedChannel second = newConnection(new MinecraftHandler(), gateway, pending);
        second.writeInbound(Unpooled.wrappedBuffer(StreamHandshake.encode("h", 25565, 47)));
        assertFalse(second.isActive());
        first.close();
        assertEquals(0, pending.get());
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] frame(byte[] packet) {
        Buf buf = new Buf().writeVarInt(packet.length);
        buf.writeBytes(packet);
        return buf.toByteArray();
    }

    private static byte[] unframe(byte[] framed) {
        Buf buf = Buf.wrap(framed);
        return buf.readBytes(buf.readVarInt());
    }

    private static byte[] readOutbound(EmbeddedChannel channel) {
        ByteBuf buf = channel.readOutbound();
        assertNotNull(buf, "expected outbound data");
        byte[] bytes = ByteBufUtil.getBytes(buf);
        buf.release();
        return bytes;
    }

    private static byte[] readAllOutbound(EmbeddedChannel channel) {
        List<byte[]> parts = new ArrayList<>();
        ByteBuf buf;
        while ((buf = channel.readOutbound()) != null) {
            parts.add(ByteBufUtil.getBytes(buf));
            buf.release();
        }
        return concat(parts);
    }

    private static byte[] concat(List<byte[]> parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
