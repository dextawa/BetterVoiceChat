package top.dext.bvc.proxy.recording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

import top.dext.bvc.proxy.config.BvcConfig;
import top.dext.bvc.proxy.platform.BvcLogger;
import top.dext.bvc.proxy.routing.AudioSink;
import top.dext.bvc.proxy.session.VoiceSession;

/**
 * Rolling recording of every forwarded frame, plus the "who heard whom" log for reports.
 * <p>
 * Network threads only enqueue; one {@code BVC-Recorder} thread writes the per-server segment
 * files, indexes utterances and deletes the oldest segments once the size cap is reached.
 */
public final class Recorder implements AudioSink {

    static final String EXTENSION = ".bvcr";
    /** A speaker silent for longer than this ends the utterance even without a MicEnd. */
    static final long UTTERANCE_GAP_MILLIS = 1500;
    private static final int QUEUE_CAPACITY = 50_000;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private sealed interface Task permits AudioTask, EndTask, ControlTask {
    }

    private record AudioTask(String server, UUID speaker, String name, long at, byte[] opus) implements Task {
    }

    private record EndTask(UUID speaker, long at) implements Task {
    }

    private record ControlTask(Runnable action) implements Task {
    }

    /** An open segment file of one sub-server. */
    private static final class Segment {
        final String server;
        final String relative;
        final SegmentFile.Writer writer;
        final long createdAt;

        Segment(String server, String relative, SegmentFile.Writer writer, long createdAt) {
            this.server = server;
            this.relative = relative;
            this.writer = writer;
            this.createdAt = createdAt;
        }
    }

    /** An utterance still being written. */
    private static final class Open {
        final Segment segment;
        final UUID speaker;
        final String name;
        final int localId;
        final long startedAt;
        final long offsetFrom;
        long lastAt;
        long offsetTo;
        int frames;

        Open(Segment segment, UUID speaker, String name, int localId, long startedAt, long offsetFrom) {
            this.segment = segment;
            this.speaker = speaker;
            this.name = name;
            this.localId = localId;
            this.startedAt = startedAt;
            this.offsetFrom = offsetFrom;
            this.lastAt = startedAt;
            this.offsetTo = offsetFrom;
        }
    }

    private final Path directory;
    private final RecordingIndex index;
    private final HeardLog heard;
    private final Supplier<BvcConfig> config;
    private final BvcLogger logger;
    private final BlockingQueue<Task> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread thread;
    private volatile boolean running = true;

    // Recorder-thread state.
    private final Map<String, Segment> segments = new HashMap<>();
    private final Map<UUID, Open> open = new HashMap<>();
    private long lastMaintenance;

    public Recorder(Path directory, RecordingIndex index, HeardLog heard, Supplier<BvcConfig> config, BvcLogger logger) {
        this.directory = directory;
        this.index = index;
        this.heard = heard;
        this.config = config;
        this.logger = logger;
        this.thread = new Thread(this::run, "BVC-Recorder");
        this.thread.setDaemon(true);
    }

    /** How long after a frame was forwarded it is guaranteed to be written and indexed (after a flush). */
    public static long settleMillis() {
        return UTTERANCE_GAP_MILLIS + 1000;
    }

    public void start() {
        thread.start();
    }

    public Path directory() {
        return directory;
    }

    public RecordingIndex index() {
        return index;
    }

    public HeardLog heard() {
        return heard;
    }

    // ------------------------------------------------------------------ AudioSink (network threads)

    @Override
    public void onAudio(String server, VoiceSession speaker, int sequence, byte[] opus, long now) {
        if (config.get().recording().enabled()) {
            offer(new AudioTask(server, speaker.uuid(), speaker.name(), now, opus));
        }
    }

    @Override
    public void onAudioEnd(String server, VoiceSession speaker, long now) {
        offer(new EndTask(speaker.uuid(), now));
    }

    @Override
    public void onHeard(VoiceSession listener, VoiceSession speaker, long now) {
        heard.heard(listener.uuid(), speaker.uuid(), now);
    }

    private void offer(Task task) {
        if (!queue.offer(task)) {
            dropped.incrementAndGet();
        }
    }

    /** Completes once everything queued so far is written and indexed, open utterances included. */
    public CompletableFuture<Void> flush() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Runnable action = () -> {
            closeAllUtterances();
            for (Segment segment : segments.values()) {
                try {
                    segment.writer.flush();
                } catch (IOException e) {
                    logger.warn("Could not flush recording " + segment.relative + ": " + e.getMessage());
                }
            }
            done.complete(null);
        };
        if (!running || !queue.offer(new ControlTask(action))) {
            done.complete(null);
        }
        return done;
    }

    /** Enforces the size cap now (normally once a minute); completes when done. */
    public CompletableFuture<Void> enforceLimit() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Runnable action = () -> {
            try {
                maintain(System.currentTimeMillis());
                done.complete(null);
            } catch (Throwable error) {
                done.completeExceptionally(error);
            }
        };
        if (!running || !queue.offer(new ControlTask(action))) {
            done.complete(null);
        }
        return done;
    }

    public void close() {
        if (!running) {
            return;
        }
        flush().orTimeout(10, TimeUnit.SECONDS).exceptionally(error -> null).join();
        running = false;
        thread.interrupt();
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ recorder thread

    private void run() {
        try {
            maintain(System.currentTimeMillis());
        } catch (Throwable error) {
            logger.error("Recording maintenance failed", error);
        }
        while (running) {
            try {
                Task task = queue.poll(1, TimeUnit.SECONDS);
                if (task != null) {
                    handle(task);
                }
                long now = System.currentTimeMillis();
                closeIdleUtterances(now);
                if (now - lastMaintenance >= 60_000) {
                    maintain(now);
                }
            } catch (InterruptedException e) {
                break;
            } catch (Throwable error) {
                logger.error("Recording failed", error);
            }
        }
        closeAllUtterances();
        for (Segment segment : segments.values()) {
            try {
                segment.writer.close();
            } catch (IOException ignored) {
                // shutting down
            }
        }
        segments.clear();
    }

    private void handle(Task task) throws IOException {
        if (task instanceof AudioTask audio) {
            write(audio);
        } else if (task instanceof EndTask end) {
            Open utterance = open.remove(end.speaker());
            if (utterance != null) {
                finish(utterance);
            }
        } else if (task instanceof ControlTask control) {
            control.action().run();
        }
    }

    private void write(AudioTask audio) throws IOException {
        Segment segment = segmentFor(audio.server(), audio.at());
        Open utterance = open.get(audio.speaker());
        if (utterance != null && (utterance.segment != segment || audio.at() - utterance.lastAt > UTTERANCE_GAP_MILLIS)) {
            open.remove(audio.speaker());
            finish(utterance);
            utterance = null;
        }
        int localId = segment.writer.localId(audio.speaker(), audio.name());
        if (utterance == null) {
            utterance = new Open(segment, audio.speaker(), audio.name(), localId, audio.at(), segment.writer.position());
            open.put(audio.speaker(), utterance);
        }
        segment.writer.frame(localId, audio.at(), audio.opus(), 0);
        utterance.lastAt = audio.at();
        utterance.offsetTo = segment.writer.position();
        utterance.frames++;
    }

    private Segment segmentFor(String server, long now) throws IOException {
        Segment segment = segments.get(server);
        long length = config.get().recording().segmentMinutes() * 60_000L;
        if (segment != null && now - segment.createdAt < length) {
            return segment;
        }
        if (segment != null) {
            closeSegment(segment);
        }
        String relative = DAY.format(Instant.ofEpochMilli(now)) + "/" + safeName(server) + "/" + now + EXTENSION;
        Path path = directory.resolve(relative);
        while (Files.exists(path)) {
            now++;
            relative = DAY.format(Instant.ofEpochMilli(now)) + "/" + safeName(server) + "/" + now + EXTENSION;
            path = directory.resolve(relative);
        }
        segment = new Segment(server, relative, new SegmentFile.Writer(path, SegmentFile.KIND_SEGMENT), now);
        segments.put(server, segment);
        return segment;
    }

    private void closeSegment(Segment segment) {
        for (Iterator<Open> it = open.values().iterator(); it.hasNext(); ) {
            Open utterance = it.next();
            if (utterance.segment == segment) {
                it.remove();
                finish(utterance);
            }
        }
        try {
            segment.writer.close();
        } catch (IOException e) {
            logger.warn("Could not close recording " + segment.relative + ": " + e.getMessage());
        }
        segments.remove(segment.server, segment);
    }

    private void closeIdleUtterances(long now) {
        for (Iterator<Open> it = open.values().iterator(); it.hasNext(); ) {
            Open utterance = it.next();
            if (now - utterance.lastAt > UTTERANCE_GAP_MILLIS) {
                it.remove();
                finish(utterance);
            }
        }
    }

    private void closeAllUtterances() {
        for (Open utterance : new ArrayList<>(open.values())) {
            finish(utterance);
        }
        open.clear();
    }

    private void finish(Open utterance) {
        try {
            // The index must never point at bytes still sitting in the buffer.
            utterance.segment.writer.flush();
        } catch (IOException e) {
            logger.warn("Could not flush recording " + utterance.segment.relative + ": " + e.getMessage());
        }
        index.insert(new RecordingIndex.Utterance(0, utterance.segment.relative, utterance.segment.server, utterance.speaker,
                utterance.name, utterance.startedAt, utterance.lastAt + 20, utterance.frames, utterance.localId,
                utterance.offsetFrom, utterance.offsetTo)).exceptionally(error -> {
                    logger.warn("Could not index an utterance: " + error);
                    return null;
                });
    }

    /** Enforces the size cap (oldest closed segments first) and reports dropped frames. */
    private void maintain(long now) throws IOException {
        lastMaintenance = now;
        heard.purge(now);
        long drops = dropped.getAndSet(0);
        if (drops > 0) {
            logger.warn("Recording fell behind and dropped " + drops + " frames");
        }
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(path -> path.toString().endsWith(EXTENSION)).forEach(files::add);
        }
        files.sort(Comparator.comparingLong(Recorder::createdAtOf));
        long total = 0;
        Map<Path, Long> sizes = new HashMap<>();
        for (Path file : files) {
            long size = Files.size(file);
            sizes.put(file, size);
            total += size;
        }
        long max = config.get().recording().maxBytes();
        List<Path> current = new ArrayList<>();
        for (Segment segment : segments.values()) {
            current.add(segment.writer.path().toAbsolutePath().normalize());
        }
        for (Path file : files) {
            if (total <= max) {
                break;
            }
            if (current.contains(file.toAbsolutePath().normalize())) {
                continue;
            }
            String relative = directory.relativize(file).toString().replace('\\', '/');
            index.deleteSegment(relative);
            Files.deleteIfExists(file);
            total -= sizes.get(file);
            deleteEmptyParents(file.getParent());
        }
    }

    private void deleteEmptyParents(Path dir) {
        try {
            while (dir != null && !dir.equals(directory) && dir.startsWith(directory)) {
                try (Stream<Path> children = Files.list(dir)) {
                    if (children.findAny().isPresent()) {
                        return;
                    }
                }
                Files.delete(dir);
                dir = dir.getParent();
            }
        } catch (IOException ignored) {
            // harmless
        }
    }

    private static long createdAtOf(Path file) {
        String name = file.getFileName().toString();
        try {
            return Long.parseLong(name.substring(0, name.length() - EXTENSION.length()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String safeName(String server) {
        String safe = server.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() || safe.startsWith(".") ? "_" + safe : safe;
    }
}
