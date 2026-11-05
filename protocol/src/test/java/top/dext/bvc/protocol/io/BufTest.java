package top.dext.bvc.protocol.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import top.dext.bvc.protocol.ProtocolException;

class BufTest {

    @Test
    void varIntRoundTripAndSize() {
        int[] values = {0, 1, 127, 128, 255, 16383, 16384, 2097151, 2097152, Integer.MAX_VALUE, -1, Integer.MIN_VALUE};
        for (int value : values) {
            Buf buf = new Buf();
            buf.writeVarInt(value);
            assertEquals(Buf.varIntSize(value), buf.readableBytes(), "size of " + value);
            assertEquals(value, buf.readVarInt());
            assertFalse(buf.isReadable());
        }
    }

    @Test
    void varLongRoundTrip() {
        long[] values = {0L, 1L, 300L, Long.MAX_VALUE, -1L, Long.MIN_VALUE};
        for (long value : values) {
            Buf buf = new Buf();
            buf.writeVarLong(value);
            assertEquals(value, buf.readVarLong());
        }
    }

    @Test
    void primitivesStringsAndUuids() {
        UUID uuid = UUID.randomUUID();
        Buf buf = new Buf(1);
        buf.writeBoolean(true).writeShort(-2).writeInt(123456789).writeLong(-987654321012L).writeFloat(48.5f)
                .writeString("你好 BVC").writeUuid(uuid).writeByteArray(new byte[] {1, 2, 3})
                .writeVarIntArray(new int[] {5, 500, 50000});

        Buf read = Buf.wrap(buf.toByteArray());
        assertEquals(true, read.readBoolean());
        assertEquals(-2, read.readShort());
        assertEquals(123456789, read.readInt());
        assertEquals(-987654321012L, read.readLong());
        assertEquals(48.5f, read.readFloat());
        assertEquals("你好 BVC", read.readString(64));
        assertEquals(uuid, read.readUuid());
        assertArrayEquals(new byte[] {1, 2, 3}, read.readByteArray(16));
        assertArrayEquals(new int[] {5, 500, 50000}, read.readVarIntArray(16));
        assertFalse(read.isReadable());
    }

    @Test
    void limitsAreEnforced() {
        Buf buf = new Buf().writeString("this string is too long");
        assertThrows(ProtocolException.class, () -> Buf.wrap(buf.toByteArray()).readString(4));

        Buf array = new Buf().writeVarInt(1000);
        assertThrows(ProtocolException.class, () -> Buf.wrap(array.toByteArray()).readByteArray(100));

        assertThrows(ProtocolException.class, () -> Buf.wrap(new byte[] {1}).readInt());

        byte[] endlessVarInt = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        assertThrows(ProtocolException.class, () -> Buf.wrap(endlessVarInt).readVarInt());
    }

    @Test
    void wrapRespectsSlice() {
        byte[] data = {9, 9, 42, 9};
        Buf buf = Buf.wrap(data, 2, 1);
        assertEquals(1, buf.readableBytes());
        assertEquals(42, buf.readByte());
        assertThrows(ProtocolException.class, buf::readByte);
    }
}
