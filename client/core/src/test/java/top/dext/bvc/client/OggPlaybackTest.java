package top.dext.bvc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import top.dext.bvc.client.audio.OpusCodec;
import top.dext.bvc.proxy.recording.Clips;

/**
 * The proxy's Ogg Opus output, fed with real Opus from the client codec, must decode in a real
 * decoder (ffmpeg, as browsers use libopus too) to the right length and with the gaps kept.
 */
class OggPlaybackTest {

    @TempDir
    Path dir;

    @Test
    void ffmpegDecodesRecordedClip() throws Exception {
        OpusCodec.Encoder encoder = new OpusCodec.Encoder(16000);
        List<Clips.TimedFrame> frames = new ArrayList<>();
        short[] pcm = new short[960];
        long at = 1_000_000;
        // 1 s of a 440 Hz tone, a 500 ms gap, then 1 s more
        for (int i = 0; i < 100; i++) {
            for (int s = 0; s < pcm.length; s++) {
                pcm[s] = (short) (8000 * Math.sin(2 * Math.PI * 440 * (i * 960 + s) / 48000.0));
            }
            frames.add(new Clips.TimedFrame(at, encoder.encode(pcm), 0));
            at += i == 49 ? 520 : 20;
        }
        Path ogg = dir.resolve("clip.ogg");
        Files.write(ogg, Clips.toOgg(frames, 1234));

        Path wav = dir.resolve("clip.wav");
        Process process;
        try {
            process = new ProcessBuilder("ffmpeg", "-v", "error", "-y", "-i", ogg.toString(), "-ac", "1", "-ar", "48000",
                    "-f", "s16le", wav.toString()).redirectErrorStream(true).start();
        } catch (java.io.IOException e) {
            Assumptions.abort("ffmpeg not installed");
            return;
        }
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.isBlank(), "decoder complained: " + output);

        byte[] raw = Files.readAllBytes(wav);
        int samples = raw.length / 2;
        // 2.5 s of audio, minus the 312-sample pre-skip
        assertEquals(125 * 960, samples, 960);
        ShortBuffer buffer = new ShortBuffer(raw);
        double toneEnergy = buffer.rms(24000, 48000);      // 0.5 s .. 1.0 s
        double gapEnergy = buffer.rms(50 * 960 + 4800, 50 * 960 + 19200);  // inside the gap
        double laterEnergy = buffer.rms(80 * 960, 100 * 960);
        assertTrue(toneEnergy > 1000, "tone " + toneEnergy);
        assertTrue(gapEnergy < toneEnergy / 20, "gap " + gapEnergy + " vs tone " + toneEnergy);
        assertTrue(laterEnergy > 1000, "after gap " + laterEnergy);
    }

    private static final class ShortBuffer {
        private final ByteBuffer bytes;

        ShortBuffer(byte[] raw) {
            this.bytes = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        }

        double rms(int from, int to) {
            double sum = 0;
            for (int i = from; i < to; i++) {
                double v = bytes.getShort(i * 2);
                sum += v * v;
            }
            return Math.sqrt(sum / (to - from));
        }
    }
}
