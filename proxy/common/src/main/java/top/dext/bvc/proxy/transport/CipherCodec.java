package top.dext.bvc.proxy.transport;

import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.ShortBufferException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageCodec;

/**
 * AES-CTR over the raw stream, below the framing layer. Installed right after authentication.
 */
final class CipherCodec extends MessageToMessageCodec<ByteBuf, ByteBuf> {

    private final Cipher decryptor;
    private final Cipher encryptor;

    CipherCodec(Cipher decryptor, Cipher encryptor) {
        this.decryptor = decryptor;
        this.encryptor = encryptor;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        out.add(process(encryptor, msg, ctx.alloc()));
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        out.add(process(decryptor, msg, ctx.alloc()));
    }

    private static ByteBuf process(Cipher cipher, ByteBuf in, ByteBufAllocator allocator) {
        int length = in.readableBytes();
        byte[] input;
        int inputOffset;
        if (in.hasArray()) {
            input = in.array();
            inputOffset = in.arrayOffset() + in.readerIndex();
        } else {
            input = new byte[length];
            in.getBytes(in.readerIndex(), input);
            inputOffset = 0;
        }
        ByteBuf result = allocator.heapBuffer(length);
        try {
            int written = cipher.update(input, inputOffset, length, result.array(), result.arrayOffset() + result.writerIndex());
            result.writerIndex(result.writerIndex() + written);
        } catch (ShortBufferException e) {
            result.release();
            throw new DecoderException(e);
        }
        in.skipBytes(length);
        return result;
    }
}
