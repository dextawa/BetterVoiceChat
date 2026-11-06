package top.dext.bvc.proxy.recording;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Wraps 20 ms mono Opus frames into an Ogg Opus file (RFC 7845) without re-encoding, so
 * browsers can decode it with {@code decodeAudioData}.
 */
public final class OggOpus {

    /** A silent 20 ms Opus frame (CELT, fullband): fills gaps so the track keeps real time. */
    static final byte[] SILENCE = {(byte) 0xF8, (byte) 0xFF, (byte) 0xFE};
    static final int SAMPLES_PER_FRAME = 960;
    private static final int PRE_SKIP = 312;
    private static final int PACKETS_PER_PAGE = 50;
    private static final int[] CRC = crcTable();

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final int serial;
    private final List<byte[]> pending = new ArrayList<>();
    private int pendingSegments;
    private int sequence;
    private long granule = PRE_SKIP;
    private boolean finished;

    public OggOpus(int serial) {
        this.serial = serial;
        writeHeaders();
    }

    /** Appends one frame (20 ms). */
    public void frame(byte[] opus) {
        int segments = opus.length / 255 + 1;
        // An Ogg page holds at most 255 lacing values.
        if (pendingSegments + segments > 255) {
            flushPage(false);
        }
        pending.add(opus);
        pendingSegments += segments;
        granule += SAMPLES_PER_FRAME;
        if (pending.size() >= PACKETS_PER_PAGE) {
            flushPage(false);
        }
    }

    /** Appends {@code count} silent frames. */
    public void silence(long count) {
        for (long i = 0; i < count; i++) {
            frame(SILENCE);
        }
    }

    public byte[] finish() {
        if (!finished) {
            finished = true;
            flushPage(true);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ internals

    private void writeHeaders() {
        byte[] head = new byte[19];
        System.arraycopy("OpusHead".getBytes(StandardCharsets.US_ASCII), 0, head, 0, 8);
        head[8] = 1;                 // version
        head[9] = 1;                 // channels
        head[10] = (byte) PRE_SKIP;
        head[11] = (byte) (PRE_SKIP >> 8);
        int rate = 48_000;
        head[12] = (byte) rate;
        head[13] = (byte) (rate >> 8);
        head[14] = (byte) (rate >> 16);
        head[15] = (byte) (rate >> 24);
        // output gain 0, mapping family 0
        page(List.of(head), 0, 0x02);

        byte[] vendor = "BetterVoiceChat".getBytes(StandardCharsets.UTF_8);
        byte[] tags = new byte[8 + 4 + vendor.length + 4];
        System.arraycopy("OpusTags".getBytes(StandardCharsets.US_ASCII), 0, tags, 0, 8);
        tags[8] = (byte) vendor.length;
        System.arraycopy(vendor, 0, tags, 12, vendor.length);
        page(List.of(tags), 0, 0);
    }

    private void flushPage(boolean last) {
        if (pending.isEmpty() && !last) {
            return;
        }
        page(new ArrayList<>(pending), granule, last ? 0x04 : 0);
        pending.clear();
        pendingSegments = 0;
    }

    private void page(List<byte[]> packets, long granulePosition, int flags) {
        ByteArrayOutputStream lacing = new ByteArrayOutputStream();
        int bodySize = 0;
        for (byte[] packet : packets) {
            int remaining = packet.length;
            while (remaining >= 255) {
                lacing.write(255);
                remaining -= 255;
            }
            lacing.write(remaining);
            bodySize += packet.length;
        }
        byte[] segments = lacing.toByteArray();
        byte[] page = new byte[27 + segments.length + bodySize];
        page[0] = 'O';
        page[1] = 'g';
        page[2] = 'g';
        page[3] = 'S';
        page[4] = 0;
        page[5] = (byte) flags;
        for (int i = 0; i < 8; i++) {
            page[6 + i] = (byte) (granulePosition >>> (8 * i));
        }
        for (int i = 0; i < 4; i++) {
            page[14 + i] = (byte) (serial >>> (8 * i));
            page[18 + i] = (byte) (sequence >>> (8 * i));
        }
        page[26] = (byte) segments.length;
        System.arraycopy(segments, 0, page, 27, segments.length);
        int pos = 27 + segments.length;
        for (byte[] packet : packets) {
            System.arraycopy(packet, 0, page, pos, packet.length);
            pos += packet.length;
        }
        int crc = 0;
        for (byte b : page) {
            crc = (crc << 8) ^ CRC[((crc >>> 24) & 0xFF) ^ (b & 0xFF)];
        }
        for (int i = 0; i < 4; i++) {
            page[22 + i] = (byte) (crc >>> (8 * i));
        }
        out.write(page, 0, page.length);
        sequence++;
    }

    private static int[] crcTable() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
            }
            table[i] = r;
        }
        return table;
    }
}
