package top.dext.bvc.proxy.transport;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import top.dext.bvc.protocol.BvcProtocol;

/**
 * Splits the stream into frames prefixed with a VarInt length (at most 3 bytes, like Minecraft).
 */
final class VarIntFrameDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.isReadable()) {
            int start = in.readerIndex();
            int length = 0;
            boolean complete = false;
            for (int i = 0; i < 3; i++) {
                if (!in.isReadable()) {
                    in.readerIndex(start);
                    return;
                }
                byte b = in.readByte();
                length |= (b & 0x7F) << (7 * i);
                if ((b & 0x80) == 0) {
                    complete = true;
                    break;
                }
            }
            if (!complete) {
                throw new CorruptedFrameException("Frame length VarInt too long");
            }
            if (length <= 0 || length > BvcProtocol.MAX_STREAM_FRAME) {
                throw new CorruptedFrameException("Bad frame length " + length);
            }
            if (in.readableBytes() < length) {
                in.readerIndex(start);
                return;
            }
            out.add(in.readRetainedSlice(length));
        }
    }
}
