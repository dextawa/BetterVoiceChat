package top.dext.bvc.proxy.transport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import top.dext.bvc.protocol.data.Transport;
import top.dext.bvc.protocol.stream.StreamHandshake;
import top.dext.bvc.protocol.stream.StreamHandshake.Detection;

/**
 * Sits first in every new client connection's pipeline and looks at the first bytes.
 * <p>
 * A BVC stream handshake: the Minecraft handlers behind it are removed and BVC's are
 * installed. Anything else: the detector removes itself and hands the bytes on untouched, so
 * normal players pay nothing after the first packet.
 */
public final class StreamDetector extends ByteToMessageDecoder {

    public static final String NAME = "bvc-detector";
    static final String IDLE = "bvc-idle";
    static final String CIPHER = "bvc-cipher";
    static final String FRAME_DECODER = "bvc-frame-decoder";
    static final String FRAME_ENCODER = "bvc-frame-encoder";
    static final String HANDLER = "bvc-handler";

    /** Enough for any handshake we would accept; more bytes without a decision = not ours. */
    private static final int MAX_PEEK = 1100;

    private final StreamGateway gateway;
    private final AtomicInteger pending;

    StreamDetector(StreamGateway gateway, AtomicInteger pending) {
        this.gateway = gateway;
        this.pending = pending;
    }

    /** Adds a detector to a fresh child channel, after a PROXY-protocol decoder if there is one. */
    public static void install(Channel channel, StreamGateway gateway, AtomicInteger pending) {
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(NAME) != null) {
            return;
        }
        String after = null;
        for (Map.Entry<String, ChannelHandler> entry : pipeline) {
            if (entry.getValue().getClass().getSimpleName().equals("HAProxyMessageDecoder")) {
                after = entry.getKey();
            }
        }
        StreamDetector detector = new StreamDetector(gateway, pending);
        if (after != null) {
            pipeline.addAfter(after, NAME, detector);
        } else {
            pipeline.addFirst(NAME, detector);
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int readable = in.readableBytes();
        int length = Math.min(readable, MAX_PEEK);
        byte[] head = new byte[length];
        in.getBytes(in.readerIndex(), head);
        Detection detection = StreamHandshake.detect(head, 0, length);

        switch (detection.kind()) {
            case NEED_MORE:
                if (readable >= MAX_PEEK) {
                    ctx.pipeline().remove(this);
                }
                return;
            case NOT_BVC:
                ctx.pipeline().remove(this);
                return;
            case BVC:
            default:
                break;
        }

        if (!gateway.config().transportPolicy().allows(Transport.STREAM)) {
            ctx.close();
            return;
        }
        if (pending.incrementAndGet() > gateway.config().maxPendingConnections()) {
            pending.decrementAndGet();
            ctx.close();
            return;
        }
        in.skipBytes(detection.consumed());
        takeOver(ctx);
    }

    private void takeOver(ChannelHandlerContext ctx) {
        ChannelPipeline pipeline = ctx.pipeline();
        List<String> names = pipeline.names();
        int self = names.indexOf(ctx.name());
        for (String name : names.subList(self + 1, names.size())) {
            if (pipeline.context(name) != null) {
                pipeline.remove(name);
            }
        }
        pipeline.addAfter(ctx.name(), IDLE, new ReadTimeoutHandler(gateway.config().idleTimeoutSeconds()));
        pipeline.addAfter(IDLE, FRAME_DECODER, new VarIntFrameDecoder());
        pipeline.addAfter(FRAME_DECODER, FRAME_ENCODER, new VarIntFrameEncoder());
        pipeline.addAfter(FRAME_ENCODER, HANDLER, new StreamHandler(gateway, pending));
        // Removing the decoder forwards any bytes that arrived with the handshake (the Auth packet).
        pipeline.remove(this);
    }
}
