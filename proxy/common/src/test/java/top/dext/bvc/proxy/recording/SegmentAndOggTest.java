package top.dext.bvc.proxy.recording;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SegmentAndOggTest {

    @TempDir
    Path dir;

    @Test
    void segmentRoundTripAndRangeRead() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Path file = dir.resolve("a/b/1.bvcr");
        long aliceFrom;
        long aliceTo;
        int aliceId;
        try (SegmentFile.Writer writer = new SegmentFile.Writer(file, SegmentFile.KIND_SEGMENT)) {
            int bobId = writer.localId(bob, "Bob");
            writer.frame(bobId, 1000, new byte[] {1}, 0);
            aliceId = writer.localId(alice, "Alice");
            aliceFrom = writer.position();
            writer.frame(aliceId, 1020, new byte[] {2, 2}, 0);
            writer.frame(bobId, 1030, new byte[] {3}, 0);
            writer.frame(aliceId, 1040, new byte[300], 0);
            aliceTo = writer.position();
            writer.frame(aliceId, 9000, new byte[] {9}, 0);
        }
        List<SegmentFile.Entry> all = new ArrayList<>();
        SegmentFile.readAll(file, all::add);
        assertEquals(5, all.size());
        assertEquals("Bob", all.get(0).name());
        assertEquals(alice, all.get(1).speaker());

        List<SegmentFile.Entry> range = new ArrayList<>();
        SegmentFile.readRange(file, aliceFrom, aliceTo, aliceId, range::add);
        assertEquals(2, range.size());
        assertEquals(1020, range.get(0).at());
        assertArrayEquals(new byte[] {2, 2}, range.get(0).opus());
        assertEquals(300, range.get(1).opus().length);
    }

    @Test
    void truncatedFileKeepsCompleteRecords() throws Exception {
        Path file = dir.resolve("t.bvcr");
        try (SegmentFile.Writer writer = new SegmentFile.Writer(file, SegmentFile.KIND_SEGMENT)) {
            int id = writer.localId(UUID.randomUUID(), "X");
            writer.frame(id, 1, new byte[] {1, 2, 3}, 0);
            writer.frame(id, 2, new byte[] {4, 5, 6}, 0);
        }
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 2));
        List<SegmentFile.Entry> all = new ArrayList<>();
        SegmentFile.readAll(file, all::add);
        assertEquals(1, all.size());
    }

    @Test
    void oggPagesAreWellFormed() {
        List<Clips.TimedFrame> frames = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            // a 100 ms gap after frame 60
            frames.add(new Clips.TimedFrame(10_000 + i * 20L + (i >= 60 ? 100 : 0), OggOpus.SILENCE, 0));
        }
        byte[] ogg = Clips.toOgg(frames, 7);
        ByteBuffer buffer = ByteBuffer.wrap(ogg).order(ByteOrder.LITTLE_ENDIAN);
        int pages = 0;
        long lastGranule = -1;
        int packets = 0;
        int pos = 0;
        while (pos < ogg.length) {
            assertEquals('O', ogg[pos]);
            assertEquals('S', ogg[pos + 3]);
            long granule = buffer.getLong(pos + 6);
            int segments = ogg[pos + 26] & 0xFF;
            int body = 0;
            for (int i = 0; i < segments; i++) {
                int lace = ogg[pos + 27 + i] & 0xFF;
                body += lace;
                if (lace < 255) {
                    packets++;
                }
            }
            if (pages == 0) {
                assertEquals(0x02, ogg[pos + 5], "first page is BOS");
            }
            lastGranule = granule;
            pos += 27 + segments + body;
            pages++;
        }
        assertEquals(ogg.length, pos);
        // OpusHead + OpusTags + 120 frames + 5 silent frames for the gap
        assertEquals(2 + 125, packets);
        assertEquals(312 + 125 * 960L, lastGranule);
        assertTrue(pages >= 4);
    }

    @Test
    void clipsSplitOnGaps() {
        UUID speaker = UUID.randomUUID();
        List<Clips.TimedFrame> frames = List.of(
                new Clips.TimedFrame(0, new byte[] {1}, 0),
                new Clips.TimedFrame(20, new byte[] {1}, SegmentFile.FLAG_HEARD),
                new Clips.TimedFrame(5000, new byte[] {1}, 0));
        List<Clips.Clip> clips = Clips.group(speaker, "S", frames);
        assertEquals(2, clips.size());
        assertTrue(clips.get(0).heard());
        assertEquals(40, clips.get(0).to());
        assertEquals(1, clips.get(1).frames().size());
    }
}
