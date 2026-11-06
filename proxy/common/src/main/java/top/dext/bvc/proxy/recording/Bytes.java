package top.dext.bvc.proxy.recording;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;

/**
 * VarInt helpers for the recording and evidence files.
 */
final class Bytes {

    private Bytes() {
    }

    static void writeVarLong(DataOutput out, long value) throws IOException {
        while ((value & ~0x7FL) != 0) {
            out.writeByte((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.writeByte((int) value);
    }

    static long readVarLong(DataInput in) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = in.readUnsignedByte();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("VarLong too long");
    }

    static int readVarInt(DataInput in, int max) throws IOException {
        long value = readVarLong(in);
        if (value < 0 || value > max) {
            throw new EOFException("Value out of range: " + value);
        }
        return (int) value;
    }

    /** Size of {@code value} as a VarLong. */
    static int varLongSize(long value) {
        int size = 1;
        while ((value & ~0x7FL) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }
}
