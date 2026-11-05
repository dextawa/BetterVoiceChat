package top.dext.bvc.protocol.io;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import top.dext.bvc.protocol.ProtocolException;

/**
 * Growable byte buffer with Minecraft-style encodings (VarInt, length-prefixed strings).
 * <p>
 * Kept free of Netty on purpose: 1.8 clients ship Netty 4.0, modern ones 4.1, and the
 * Bukkit plugin-message API works on plain byte arrays anyway.
 */
public final class Buf {

    private byte[] data;
    private int readerIndex;
    private int writerIndex;

    public Buf() {
        this(64);
    }

    public Buf(int initialCapacity) {
        this.data = new byte[Math.max(initialCapacity, 16)];
    }

    private Buf(byte[] data, int readerIndex, int writerIndex) {
        this.data = data;
        this.readerIndex = readerIndex;
        this.writerIndex = writerIndex;
    }

    /** Wraps an array for reading without copying it. */
    public static Buf wrap(byte[] data) {
        return new Buf(data, 0, data.length);
    }

    public static Buf wrap(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException("offset=" + offset + " length=" + length + " size=" + data.length);
        }
        return new Buf(data, offset, offset + length);
    }

    public int readableBytes() {
        return writerIndex - readerIndex;
    }

    public boolean isReadable() {
        return writerIndex > readerIndex;
    }

    public int readerIndex() {
        return readerIndex;
    }

    public int writerIndex() {
        return writerIndex;
    }

    /** Backing array; valid bytes are {@code [readerIndex, writerIndex)}. */
    public byte[] array() {
        return data;
    }

    public byte[] toByteArray() {
        return Arrays.copyOfRange(data, readerIndex, writerIndex);
    }

    public Buf clear() {
        readerIndex = 0;
        writerIndex = 0;
        return this;
    }

    // ---------------------------------------------------------------- writing

    private void ensureWritable(int extra) {
        int needed = writerIndex + extra;
        if (needed > data.length) {
            int newSize = Math.max(needed, data.length << 1);
            data = Arrays.copyOf(data, newSize);
        }
    }

    public Buf writeByte(int value) {
        ensureWritable(1);
        data[writerIndex++] = (byte) value;
        return this;
    }

    public Buf writeBoolean(boolean value) {
        return writeByte(value ? 1 : 0);
    }

    public Buf writeShort(int value) {
        ensureWritable(2);
        data[writerIndex++] = (byte) (value >>> 8);
        data[writerIndex++] = (byte) value;
        return this;
    }

    public Buf writeInt(int value) {
        ensureWritable(4);
        data[writerIndex++] = (byte) (value >>> 24);
        data[writerIndex++] = (byte) (value >>> 16);
        data[writerIndex++] = (byte) (value >>> 8);
        data[writerIndex++] = (byte) value;
        return this;
    }

    public Buf writeLong(long value) {
        writeInt((int) (value >>> 32));
        return writeInt((int) value);
    }

    public Buf writeFloat(float value) {
        return writeInt(Float.floatToIntBits(value));
    }

    public Buf writeVarInt(int value) {
        ensureWritable(5);
        while ((value & ~0x7F) != 0) {
            data[writerIndex++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        data[writerIndex++] = (byte) value;
        return this;
    }

    public Buf writeVarLong(long value) {
        ensureWritable(10);
        while ((value & ~0x7FL) != 0) {
            data[writerIndex++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        data[writerIndex++] = (byte) value;
        return this;
    }

    public Buf writeBytes(byte[] src) {
        return writeBytes(src, 0, src.length);
    }

    public Buf writeBytes(byte[] src, int offset, int length) {
        ensureWritable(length);
        System.arraycopy(src, offset, data, writerIndex, length);
        writerIndex += length;
        return this;
    }

    /** VarInt length followed by the bytes. */
    public Buf writeByteArray(byte[] src) {
        writeVarInt(src.length);
        return writeBytes(src);
    }

    public Buf writeString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return writeByteArray(bytes);
    }

    public Buf writeUuid(UUID uuid) {
        writeLong(uuid.getMostSignificantBits());
        return writeLong(uuid.getLeastSignificantBits());
    }

    public Buf writeVarIntArray(int[] values) {
        writeVarInt(values.length);
        for (int value : values) {
            writeVarInt(value);
        }
        return this;
    }

    // ---------------------------------------------------------------- reading

    private void checkReadable(int length) {
        if (length < 0 || readerIndex + length > writerIndex) {
            throw new ProtocolException("Tried to read " + length + " bytes but only " + readableBytes() + " left");
        }
    }

    public byte readByte() {
        checkReadable(1);
        return data[readerIndex++];
    }

    public int readUnsignedByte() {
        return readByte() & 0xFF;
    }

    public boolean readBoolean() {
        return readByte() != 0;
    }

    public short readShort() {
        checkReadable(2);
        int value = ((data[readerIndex] & 0xFF) << 8) | (data[readerIndex + 1] & 0xFF);
        readerIndex += 2;
        return (short) value;
    }

    public int readUnsignedShort() {
        return readShort() & 0xFFFF;
    }

    public int readInt() {
        checkReadable(4);
        int value = ((data[readerIndex] & 0xFF) << 24)
                | ((data[readerIndex + 1] & 0xFF) << 16)
                | ((data[readerIndex + 2] & 0xFF) << 8)
                | (data[readerIndex + 3] & 0xFF);
        readerIndex += 4;
        return value;
    }

    public long readLong() {
        long high = readInt() & 0xFFFFFFFFL;
        long low = readInt() & 0xFFFFFFFFL;
        return (high << 32) | low;
    }

    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    public int readVarInt() {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            byte b = readByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new ProtocolException("VarInt too long");
    }

    public long readVarLong() {
        long value = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            byte b = readByte();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new ProtocolException("VarLong too long");
    }

    public byte[] readBytes(int length) {
        checkReadable(length);
        byte[] out = Arrays.copyOfRange(data, readerIndex, readerIndex + length);
        readerIndex += length;
        return out;
    }

    public byte[] readByteArray(int maxLength) {
        int length = readVarInt();
        if (length < 0 || length > maxLength) {
            throw new ProtocolException("Byte array length " + length + " exceeds limit " + maxLength);
        }
        return readBytes(length);
    }

    /** Reads a string whose UTF-8 encoding is at most {@code maxBytes} long. */
    public String readString(int maxBytes) {
        int length = readVarInt();
        if (length < 0 || length > maxBytes) {
            throw new ProtocolException("String length " + length + " exceeds limit " + maxBytes);
        }
        checkReadable(length);
        String value = new String(data, readerIndex, length, StandardCharsets.UTF_8);
        readerIndex += length;
        return value;
    }

    public UUID readUuid() {
        return new UUID(readLong(), readLong());
    }

    public int[] readVarIntArray(int maxCount) {
        int count = readCount(maxCount);
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = readVarInt();
        }
        return values;
    }

    /** Reads a VarInt element count and checks it against a limit. */
    public int readCount(int maxCount) {
        int count = readVarInt();
        if (count < 0 || count > maxCount) {
            throw new ProtocolException("Element count " + count + " exceeds limit " + maxCount);
        }
        return count;
    }

    public void skip(int length) {
        checkReadable(length);
        readerIndex += length;
    }

    /** Number of bytes {@link #writeVarInt(int)} produces for {@code value}. */
    public static int varIntSize(int value) {
        if ((value & 0xFFFFFF80) == 0) {
            return 1;
        }
        if ((value & 0xFFFFC000) == 0) {
            return 2;
        }
        if ((value & 0xFFE00000) == 0) {
            return 3;
        }
        if ((value & 0xF0000000) == 0) {
            return 4;
        }
        return 5;
    }
}
