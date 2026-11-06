package top.dext.bvc.proxy.recording;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The append-only file format shared by rolling segments ({@code .bvcr}) and report evidence
 * ({@code .bvce}).
 *
 * <pre>
 * header   "BVC" kind(1 byte: 'R' segment, 'E' evidence) version(1)
 * record   type(1) ...
 *   1 speaker   localId(varint) uuid(16) name(UTF)
 *   2 frame     localId(varint) at(varlong millis) length(varint) opus
 *   3 frame     like 2, then flags(1)          (evidence only; bit 0 = heard by the reporter)
 * </pre>
 *
 * A file is self-describing when read from the start; the index stores byte ranges and local
 * ids so one utterance can be read without the rest of the file.
 */
public final class SegmentFile {

    public static final byte KIND_SEGMENT = 'R';
    public static final byte KIND_EVIDENCE = 'E';
    static final int VERSION = 1;
    static final int HEADER_SIZE = 5;
    static final int MAX_FRAME = 4000;

    private static final int SPEAKER = 1;
    private static final int FRAME = 2;
    private static final int FLAGGED_FRAME = 3;

    /** Bit 0 of an evidence frame's flags. */
    public static final int FLAG_HEARD = 1;

    private SegmentFile() {
    }

    /** One frame as read back. */
    public record Entry(int localId, UUID speaker, String name, long at, byte[] opus, int flags) {
    }

    @FunctionalInterface
    public interface EntryConsumer {
        void accept(Entry entry);
    }

    // ------------------------------------------------------------------ writing

    /** Appends records and keeps track of the file length. Not thread-safe. */
    public static final class Writer implements AutoCloseable {

        private final Path path;
        private final DataOutputStream out;
        private final boolean flagged;
        private final Map<UUID, Integer> localIds = new HashMap<>();
        private long position;

        public Writer(Path path, byte kind) throws IOException {
            this.path = path;
            this.flagged = kind == KIND_EVIDENCE;
            Files.createDirectories(path.getParent());
            OutputStream file = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            this.out = new DataOutputStream(new BufferedOutputStream(file, 64 * 1024));
            out.write(new byte[] {'B', 'V', 'C', kind, VERSION});
            position = HEADER_SIZE;
        }

        public Path path() {
            return path;
        }

        /** Current length, i.e. the offset the next record starts at. */
        public long position() {
            return position;
        }

        /** Local id of {@code speaker} in this file, writing a speaker record the first time. */
        public int localId(UUID speaker, String name) throws IOException {
            Integer id = localIds.get(speaker);
            if (id != null) {
                return id;
            }
            int newId = localIds.size() + 1;
            localIds.put(speaker, newId);
            int before = out.size();
            out.writeByte(SPEAKER);
            Bytes.writeVarLong(out, newId);
            out.writeLong(speaker.getMostSignificantBits());
            out.writeLong(speaker.getLeastSignificantBits());
            out.writeUTF(name.length() > 64 ? name.substring(0, 64) : name);
            position += out.size() - before;
            return newId;
        }

        public void frame(int localId, long at, byte[] opus, int flags) throws IOException {
            if (opus.length > MAX_FRAME) {
                return;
            }
            int before = out.size();
            out.writeByte(flagged ? FLAGGED_FRAME : FRAME);
            Bytes.writeVarLong(out, localId);
            Bytes.writeVarLong(out, at);
            Bytes.writeVarLong(out, opus.length);
            out.write(opus);
            if (flagged) {
                out.writeByte(flags);
            }
            // DataOutputStream.size() saturates at Integer.MAX_VALUE; segments stay far below.
            position += out.size() - before;
        }

        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    // ------------------------------------------------------------------ reading

    /** Reads every frame of a whole file. */
    public static void readAll(Path path, EntryConsumer consumer) throws IOException {
        byte[] data = Files.readAllBytes(path);
        if (data.length < HEADER_SIZE || data[0] != 'B' || data[1] != 'V' || data[2] != 'C' || data[4] != VERSION) {
            throw new IOException("Not a BVC recording: " + path);
        }
        parse(data, HEADER_SIZE, data.length, -1, new HashMap<>(), consumer);
    }

    /**
     * Reads frames of one speaker between two offsets (an utterance from the index). Speaker
     * records before {@code from} are not seen, so entries carry the local id but no UUID.
     */
    public static void readRange(Path path, long from, long to, int localId, EntryConsumer consumer) throws IOException {
        if (to <= from) {
            return;
        }
        byte[] data;
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long end = Math.min(to, file.length());
            if (end <= from) {
                return;
            }
            data = new byte[(int) (end - from)];
            file.seek(from);
            file.readFully(data);
        }
        parse(data, 0, data.length, localId, new HashMap<>(), consumer);
    }

    private record Speaker(UUID uuid, String name) {
    }

    private static void parse(byte[] data, int start, int end, int onlyLocalId, Map<Integer, Speaker> speakers,
                              EntryConsumer consumer) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, start, end - start));
        try {
            while (in.available() > 0) {
                int type = in.readUnsignedByte();
                switch (type) {
                    case SPEAKER -> {
                        int id = Bytes.readVarInt(in, Integer.MAX_VALUE);
                        UUID uuid = new UUID(in.readLong(), in.readLong());
                        speakers.put(id, new Speaker(uuid, in.readUTF()));
                    }
                    case FRAME, FLAGGED_FRAME -> {
                        int id = Bytes.readVarInt(in, Integer.MAX_VALUE);
                        long at = Bytes.readVarLong(in);
                        byte[] opus = new byte[Bytes.readVarInt(in, MAX_FRAME)];
                        in.readFully(opus);
                        int flags = type == FLAGGED_FRAME ? in.readUnsignedByte() : 0;
                        if (onlyLocalId < 0 || id == onlyLocalId) {
                            Speaker speaker = speakers.get(id);
                            consumer.accept(new Entry(id, speaker == null ? null : speaker.uuid(),
                                    speaker == null ? null : speaker.name(), at, opus, flags));
                        }
                    }
                    default -> throw new IOException("Corrupt recording (record type " + type + ")");
                }
            }
        } catch (EOFException e) {
            // A record cut off by a crash: everything before it is still good.
        }
    }
}
