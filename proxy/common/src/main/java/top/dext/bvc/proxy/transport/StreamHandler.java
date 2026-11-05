package top.dext.bvc.proxy.transport;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import top.dext.bvc.protocol.ProtocolException;
import top.dext.bvc.protocol.crypto.BvcCrypto;
import top.dext.bvc.protocol.crypto.SessionKeys;
import top.dext.bvc.protocol.packet.ClientToProxy;
import top.dext.bvc.protocol.packet.Packet;
import top.dext.bvc.protocol.packet.ProxyToClient;
import top.dext.bvc.proxy.session.StreamConnection;
import top.dext.bvc.proxy.session.VoiceSession;

/**
 * One stream connection: authenticates it, then turns frames into packets for the core and
 * batches outgoing packets into one flush per event-loop turn.
 */
final class StreamHandler extends SimpleChannelInboundHandler<ByteBuf> implements StreamConnection {

    /** Packets queued for a slow client beyond this are dropped (stale audio is useless). */
    private static final int MAX_QUEUED = 512;

    private enum Phase {
        AWAIT_AUTH,
        AUTHENTICATED,
        CLOSED
    }

    private final StreamGateway gateway;
    private final AtomicInteger pending;
    private final AtomicBoolean pendingReleased = new AtomicBoolean();
    private final Queue<byte[]> outbound = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();

    private volatile Phase phase = Phase.AWAIT_AUTH;
    private volatile Channel channel;
    private volatile VoiceSession session;
    private ScheduledFuture<?> authTimeout;

    StreamHandler(StreamGateway gateway, AtomicInteger pending) {
        this.gateway = gateway;
        this.pending = pending;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        channel = ctx.channel();
        authTimeout = ctx.executor().schedule(() -> {
            if (phase == Phase.AWAIT_AUTH) {
                ctx.close();
            }
        }, gateway.config().authTimeoutMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
        Packet<ClientToProxy.Handler> packet;
        try {
            packet = ClientToProxy.REGISTRY.decode(ByteBufUtil.getBytes(frame));
        } catch (ProtocolException e) {
            gateway.logger().debug("Bad stream packet from " + ctx.channel().remoteAddress() + ": " + e.getMessage());
            ctx.close();
            return;
        }
        if (phase == Phase.AWAIT_AUTH) {
            if (packet instanceof ClientToProxy.Auth auth) {
                authenticate(ctx, auth);
            } else {
                ctx.close();
            }
            return;
        }
        VoiceSession current = session;
        if (phase == Phase.AUTHENTICATED && current != null) {
            gateway.onStreamPacket(current, packet);
        }
    }

    private void authenticate(ChannelHandlerContext ctx, ClientToProxy.Auth auth) {
        VoiceSession candidate = gateway.sessionForAuth(auth.sessionId);
        byte[] token = candidate == null ? null : candidate.consumeToken(System.currentTimeMillis());
        if (token == null || !BvcCrypto.verifyAuthMac(token, auth.publicKey, auth.mac)) {
            reject(ctx, "Authentication failed");
            return;
        }
        SessionKeys keys;
        byte[] serverPublicKey;
        try {
            KeyPair serverPair = BvcCrypto.generateKeyPair();
            byte[] secret = BvcCrypto.agree(serverPair.getPrivate(), BvcCrypto.decodePublicKey(auth.publicKey));
            keys = BvcCrypto.deriveSessionKeys(secret, token);
            serverPublicKey = BvcCrypto.encodePublicKey(serverPair.getPublic());
        } catch (GeneralSecurityException e) {
            reject(ctx, "Key exchange failed");
            return;
        }

        // The result goes out in plain text; everything after it is encrypted.
        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                ProxyToClient.REGISTRY.encode(new ProxyToClient.AuthResult(true, serverPublicKey, ""))));
        ctx.pipeline().addAfter(StreamDetector.IDLE, StreamDetector.CIPHER, new CipherCodec(keys.serverInbound(), keys.serverOutbound()));

        authTimeout.cancel(false);
        releasePending();
        session = candidate;
        phase = Phase.AUTHENTICATED;
        gateway.onStreamAuthenticated(candidate, this);
    }

    private void reject(ChannelHandlerContext ctx, String message) {
        phase = Phase.CLOSED;
        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                        ProxyToClient.REGISTRY.encode(new ProxyToClient.AuthResult(false, new byte[0], message))))
                .addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        phase = Phase.CLOSED;
        releasePending();
        if (authTimeout != null) {
            authTimeout.cancel(false);
        }
        outbound.clear();
        VoiceSession current = session;
        if (current != null) {
            gateway.onStreamClosed(current, this);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        gateway.logger().debug("Stream connection " + ctx.channel().remoteAddress() + " failed: " + cause);
        ctx.close();
    }

    private void releasePending() {
        if (pendingReleased.compareAndSet(false, true)) {
            pending.decrementAndGet();
        }
    }

    // ------------------------------------------------------------------ StreamConnection

    @Override
    public void send(byte[] packet) {
        if (phase != Phase.AUTHENTICATED) {
            return;
        }
        if (queued.incrementAndGet() > MAX_QUEUED) {
            queued.decrementAndGet();
            return;
        }
        outbound.add(packet);
        if (drainScheduled.compareAndSet(false, true)) {
            channel.eventLoop().execute(this::drain);
        }
    }

    private void drain() {
        drainScheduled.set(false);
        Channel ch = channel;
        boolean wrote = false;
        byte[] packet;
        while ((packet = outbound.poll()) != null) {
            queued.decrementAndGet();
            if (phase == Phase.AUTHENTICATED) {
                ch.write(Unpooled.wrappedBuffer(packet), ch.voidPromise());
                wrote = true;
            }
        }
        if (wrote) {
            ch.flush();
        }
    }

    @Override
    public void close() {
        Channel ch = channel;
        if (ch != null) {
            ch.close();
        }
    }

    @Override
    public boolean isOpen() {
        Channel ch = channel;
        return phase == Phase.AUTHENTICATED && ch != null && ch.isActive();
    }
}
