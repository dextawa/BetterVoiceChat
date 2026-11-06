package top.dext.bvc.proxy.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import top.dext.bvc.proxy.config.BvcConfig;
import top.dext.bvc.proxy.platform.BvcLogger;
import top.dext.bvc.proxy.recording.Clips;
import top.dext.bvc.proxy.recording.HeardLog;
import top.dext.bvc.proxy.recording.Recorder;
import top.dext.bvc.proxy.recording.RecordingIndex;
import top.dext.bvc.proxy.recording.SegmentFile;

/**
 * {@code /vreport}: stores the report, waits for the "after" part of the window, then copies
 * the relevant frames out of the rolling recording into a separate evidence file that the
 * size cap never deletes.
 * <p>
 * The evidence holds everything the reported player and the reporter said in the window, plus
 * every frame the reporter heard from anyone else (flagged as heard).
 */
public final class ReportManager {

    public enum Result {
        CREATED,
        COOLDOWN,
        SELF,
        DISABLED
    }

    /** {@code id} is 0 unless {@code result} is {@link Result#CREATED}. */
    public record Filed(Result result, long id) {
    }

    /** Called with a report once its evidence is ready (to notify staff). */
    private final Consumer<ReportStore.Report> onReady;
    private final ReportStore store;
    private final Recorder recorder;
    private final Path directory;
    private final Supplier<BvcConfig> config;
    private final BvcLogger logger;
    private final Map<UUID, Long> lastReport = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BVC-Reports");
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.concurrent.ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BVC-Reports-Timer");
        thread.setDaemon(true);
        return thread;
    });

    public ReportManager(ReportStore store, Recorder recorder, Path directory, Supplier<BvcConfig> config, BvcLogger logger,
                         Consumer<ReportStore.Report> onReady) {
        this.store = store;
        this.recorder = recorder;
        this.directory = directory;
        this.config = config;
        this.logger = logger;
        this.onReady = onReady;
    }

    public ReportStore store() {
        return store;
    }

    /** Builds evidence for reports left collecting by a restart. */
    public void resumePending() {
        try {
            for (ReportStore.Report report : store.collectingSync()) {
                schedule(report, Math.max(0, report.windowTo() - System.currentTimeMillis()) + Recorder.settleMillis());
            }
        } catch (Exception e) {
            logger.warn("Could not resume pending reports: " + e.getMessage());
        }
    }

    public void close() {
        timer.shutdownNow();
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ creating

    /**
     * Files a report. {@code now} is the report time; the evidence window is
     * {@code [now - before, now + after]}.
     */
    public CompletableFuture<Filed> report(UUID reporter, String reporterName, UUID target, String targetName, String server,
                                            String reason, long now, boolean bypassCooldown) {
        BvcConfig.Reports cfg = config.get().reports();
        if (!cfg.enabled()) {
            return CompletableFuture.completedFuture(new Filed(Result.DISABLED, 0));
        }
        if (reporter.equals(target)) {
            return CompletableFuture.completedFuture(new Filed(Result.SELF, 0));
        }
        Long last = lastReport.get(reporter);
        if (!bypassCooldown && last != null && now - last < cfg.cooldownSeconds() * 1000L) {
            return CompletableFuture.completedFuture(new Filed(Result.COOLDOWN, 0));
        }
        lastReport.put(reporter, now);
        ReportStore.Report draft = new ReportStore.Report(0, reporter, reporterName, target, targetName, server,
                reason == null ? "" : reason, now, now - cfg.beforeSeconds() * 1000L, now + cfg.afterSeconds() * 1000L,
                ReportStore.Status.COLLECTING, null, 0, null);
        return store.insert(draft).thenApply(report -> {
            schedule(report, cfg.afterSeconds() * 1000L + Recorder.settleMillis());
            return new Filed(Result.CREATED, report.id());
        });
    }

    private void schedule(ReportStore.Report report, long delayMillis) {
        timer.schedule(() -> executor.execute(() -> build(report)), delayMillis, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ evidence

    public Path evidencePath(long id) {
        return directory.resolve(id + ".bvce");
    }

    private void build(ReportStore.Report report) {
        try {
            recorder.flush().get(30, TimeUnit.SECONDS);
            writeEvidence(report);
            store.setStatus(report.id(), ReportStore.Status.OPEN, null, 0, null).get(30, TimeUnit.SECONDS);
            onReady.accept(report);
        } catch (Exception e) {
            logger.error("Could not build the evidence of report #" + report.id(), e);
        }
    }

    /** Writes the evidence file now (normally done once the window has passed). */
    public void writeEvidence(ReportStore.Report report) throws Exception {
        long from = report.windowFrom();
        long to = report.windowTo();
        List<HeardLog.Interval> heard = recorder.heard().intervals(report.reporter(), from, to);
        Set<UUID> speakers = new LinkedHashSet<>();
        speakers.add(report.target());
        speakers.add(report.reporter());
        Map<UUID, List<HeardLog.Interval>> heardBySpeaker = new HashMap<>();
        for (HeardLog.Interval interval : heard) {
            speakers.add(interval.speaker());
            heardBySpeaker.computeIfAbsent(interval.speaker(), uuid -> new ArrayList<>()).add(interval);
        }

        List<RecordingIndex.Utterance> utterances = recorder.index().overlapping(from, to, speakers).get(30, TimeUnit.SECONDS);
        record Picked(UUID speaker, String name, long at, byte[] opus, int flags) {
        }
        List<Picked> picked = new ArrayList<>();
        for (RecordingIndex.Utterance utterance : utterances) {
            Path segment = recorder.directory().resolve(utterance.segment());
            boolean always = utterance.speaker().equals(report.target()) || utterance.speaker().equals(report.reporter());
            List<HeardLog.Interval> intervals = heardBySpeaker.getOrDefault(utterance.speaker(), List.of());
            try {
                SegmentFile.readRange(segment, utterance.offsetFrom(), utterance.offsetTo(), utterance.localId(), entry -> {
                    if (entry.at() < from || entry.at() > to) {
                        return;
                    }
                    boolean wasHeard = false;
                    for (HeardLog.Interval interval : intervals) {
                        if (entry.at() >= interval.from() - 20 && entry.at() <= interval.to() + 20) {
                            wasHeard = true;
                            break;
                        }
                    }
                    if (always || wasHeard) {
                        picked.add(new Picked(utterance.speaker(), utterance.speakerName(), entry.at(), entry.opus(),
                                wasHeard ? SegmentFile.FLAG_HEARD : 0));
                    }
                });
            } catch (IOException e) {
                // The segment was rotated out meanwhile, or never fully written.
                logger.debug("Skipped part of report #" + report.id() + ": " + e.getMessage());
            }
        }
        picked.sort(Comparator.comparingLong(Picked::at));

        Path path = evidencePath(report.id());
        Files.deleteIfExists(path);
        try (SegmentFile.Writer writer = new SegmentFile.Writer(path, SegmentFile.KIND_EVIDENCE)) {
            for (Picked frame : picked) {
                writer.frame(writer.localId(frame.speaker(), frame.name()), frame.at(), frame.opus(), frame.flags());
            }
        }
    }

    /** The evidence as clips, in time order; empty if it is missing. */
    public List<Clips.Clip> clips(long id) throws IOException {
        Path path = evidencePath(id);
        if (!Files.exists(path)) {
            return List.of();
        }
        Map<UUID, List<Clips.TimedFrame>> bySpeaker = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        SegmentFile.readAll(path, entry -> {
            bySpeaker.computeIfAbsent(entry.speaker(), uuid -> new ArrayList<>())
                    .add(new Clips.TimedFrame(entry.at(), entry.opus(), entry.flags()));
            names.put(entry.speaker(), entry.name());
        });
        List<Clips.Clip> clips = new ArrayList<>();
        for (Map.Entry<UUID, List<Clips.TimedFrame>> speaker : bySpeaker.entrySet()) {
            clips.addAll(Clips.group(speaker.getKey(), names.get(speaker.getKey()), speaker.getValue()));
        }
        clips.sort(Comparator.comparingLong(Clips.Clip::from));
        return clips;
    }

    /** Deletes handled reports (and their evidence) older than {@code reports.keep-days}. */
    public void purgeExpired(long now) {
        long cutoff = now - config.get().reports().keepDays() * 86_400_000L;
        store.expired(cutoff).thenAccept(ids -> {
            for (long id : ids) {
                try {
                    Files.deleteIfExists(evidencePath(id));
                    store.delete(id);
                } catch (IOException e) {
                    logger.warn("Could not delete the evidence of report #" + id + ": " + e.getMessage());
                }
            }
        });
        lastReport.entrySet().removeIf(entry -> now - entry.getValue() > 86_400_000L);
    }
}
