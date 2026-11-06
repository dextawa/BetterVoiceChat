package top.dext.bvc.proxy.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import top.dext.bvc.protocol.data.VoiceMode;
import top.dext.bvc.protocol.packet.ClientToProxy;
import top.dext.bvc.proxy.TestLogger;
import top.dext.bvc.proxy.recording.Clips;
import top.dext.bvc.proxy.recording.HeardLog;
import top.dext.bvc.proxy.recording.Recorder;
import top.dext.bvc.proxy.recording.RecordingIndex;
import top.dext.bvc.proxy.report.ReportManager;
import top.dext.bvc.proxy.report.ReportStore;
import top.dext.bvc.proxy.session.VoiceSession;

/**
 * Router → recorder → report evidence, with real files and an in-memory database.
 */
class RecordingReportTest {

    @TempDir
    Path data;

    /** Speaks {@code frames} frames, one per 20 ms of harness time. */
    private static void talk(RouterHarness h, VoiceSession speaker, int frames) {
        for (int i = 0; i < frames; i++) {
            h.speak(speaker);
            h.clock.advance(20);
        }
        h.router.onMicEnd(speaker, new ClientToProxy.MicEnd(0));
    }

    @Test
    void evidenceHoldsTargetReporterAndWhatTheReporterHeard() throws Exception {
        try (RouterHarness h = new RouterHarness()) {
            h.clock.set(System.currentTimeMillis());
            VoiceSession reporter = h.join("Reporter", "lobby");
            VoiceSession target = h.join("Target", "lobby");
            VoiceSession nearby = h.join("Nearby", "lobby");
            VoiceSession faraway = h.join("Faraway", "lobby");
            VoiceSession otherServer = h.join("Other", "bedwars");
            h.setupServer("lobby", VoiceMode.PROXIMITY, reporter, target, nearby, faraway);
            h.setupServer("bedwars", VoiceMode.FREE, otherServer);
            // reporter hears target and nearby; nobody hears faraway
            h.neighbors("lobby", target, reporter);
            h.neighbors("lobby", nearby, reporter);
            h.neighbors("lobby", reporter, target, nearby);
            h.neighbors("lobby", faraway);

            Recorder recorder = new Recorder(data.resolve("recordings"), new RecordingIndex(h.database),
                    new HeardLog(3_600_000L), () -> h.config, new TestLogger());
            recorder.start();
            h.router.setSink(recorder);
            ReportStore store = new ReportStore(h.database);
            Set<Long> ready = new HashSet<>();
            ReportManager reports = new ReportManager(store, recorder, data.resolve("reports"), () -> h.config,
                    new TestLogger(), report -> ready.add(report.id()));
            try {
                talk(h, target, 25);
                h.clock.advance(3000);
                talk(h, nearby, 10);
                h.clock.advance(3000);
                talk(h, faraway, 10);
                talk(h, otherServer, 10);
                talk(h, reporter, 5);
                recorder.flush().get(10, TimeUnit.SECONDS);

                long now = h.clock.millis();
                ReportStore.Report report = store.insert(new ReportStore.Report(0, reporter.uuid(), "Reporter", target.uuid(),
                        "Target", "lobby", "insults", now, now - 60_000, now, ReportStore.Status.COLLECTING, null, 0, null))
                        .get(10, TimeUnit.SECONDS);
                reports.writeEvidence(report);

                List<Clips.Clip> clips = reports.clips(report.id());
                Set<UUID> speakers = new HashSet<>();
                for (Clips.Clip clip : clips) {
                    speakers.add(clip.speaker());
                }
                assertEquals(Set.of(target.uuid(), nearby.uuid(), reporter.uuid()), speakers);
                Clips.Clip targetClip = clips.stream().filter(c -> c.speaker().equals(target.uuid())).findFirst().orElseThrow();
                assertEquals(25, targetClip.frames().size());
                assertTrue(targetClip.heard());
                Clips.Clip reporterClip = clips.stream().filter(c -> c.speaker().equals(reporter.uuid())).findFirst().orElseThrow();
                assertFalse(reporterClip.heard(), "the reporter does not hear themself");
                assertTrue(Clips.toOgg(targetClip.frames(), 1).length > 25 * RouterHarness.OPUS.length);

                // The panel's search finds the utterance by speaker and plays it.
                List<RecordingIndex.Utterance> found = recorder.index().search(faraway.uuid(), "lobby", now - 60_000, now, 10)
                        .get(10, TimeUnit.SECONDS);
                assertEquals(1, found.size());
                assertEquals(10, found.get(0).frames());
            } finally {
                recorder.close();
                reports.close();
            }
        }
    }

    @Test
    void sizeCapDeletesOldestClosedSegments() throws Exception {
        try (RouterHarness h = new RouterHarness("recording:\n  max-size-mb: 16\n  segment-minutes: 5\n")) {
            h.clock.set(System.currentTimeMillis() - 3_600_000L);
            VoiceSession a = h.join("A", "lobby");
            VoiceSession b = h.join("B", "lobby");
            h.setupServer("lobby", VoiceMode.FREE, a, b);
            Recorder recorder = new Recorder(data.resolve("recordings"), new RecordingIndex(h.database),
                    new HeardLog(60_000L), () -> h.config, new TestLogger());
            recorder.start();
            h.router.setSink(recorder);
            try {
                byte[] big = new byte[3900];
                // ~6 segments of ~4 MB each: well over the 16 MB cap
                for (int segment = 0; segment < 6; segment++) {
                    for (int i = 0; i < 1000; i++) {
                        h.router.onMicAudio(a, new ClientToProxy.MicAudio(i, 0, big));
                        h.clock.advance(20);
                    }
                    h.clock.advance(5 * 60_000L);
                }
                recorder.flush().get(30, TimeUnit.SECONDS);
                recorder.enforceLimit().get(30, TimeUnit.SECONDS);
                long total;
                try (Stream<Path> files = Files.walk(data.resolve("recordings"))) {
                    total = files.filter(p -> p.toString().endsWith(".bvcr")).mapToLong(p -> p.toFile().length()).sum();
                }
                // Only whole closed segments are deleted, so the open one may push it slightly over.
                assertTrue(total <= (16L << 20) + 4_100_000L, "total " + total);
                assertTrue(total >= 8L << 20, "kept the newest segments, total " + total);
            } finally {
                recorder.close();
            }
        }
    }
}
