package top.dext.bvc.proxy.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Prefixes each outgoing packet with its VarInt length.
 */
@ChannelHandler.Sharable
final class VarIntFrameEncoder extends MessageToByteEncoder<ByteBuf> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        int length = msg.readableBytes();
        out.ensureWritable(length + 3);
        while ((length & ~0x7F) != 0) {
            out.writeByte((length & 0x7F) | 0x80);
            length >>>= 7;
        }
        out.writeByte(length);
        out.writeBytes(msg);
    }
}
