package top.dext.bvc.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.data.AudioCategory;

class AudioTest {

    private static float[] gains(double[] listener, double[] source, float radius) {
        float[] out = new float[2];
        VoiceMixer.spatialGains(listener, source, radius, out);
        return out;
    }

    @Test
    void panningFollowsMinecraftYaw() {
        // Facing south (yaw 0): east (+x) is on the left.
        float[] east = gains(new double[] {0, 64, 0, 0}, new double[] {5, 64, 0}, 20);
        assertTrue(east[0] > east[1], "east is left when facing south");
        // Facing north (yaw 180): east is on the right.
        float[] eastNorth = gains(new double[] {0, 64, 0, 180}, new double[] {5, 64, 0}, 20);
        assertTrue(eastNorth[1] > eastNorth[0]);
        // Facing west (yaw 90): north (-z) is on the right.
        float[] north = gains(new double[] {0, 64, 0, 90}, new double[] {0, 64, -5}, 20);
        assertTrue(north[1] > north[0]);
        // Straight ahead: centred.
        float[] ahead = gains(new double[] {0, 64, 0, 0}, new double[] {0, 64, 5}, 20);
        assertEquals(ahead[0], ahead[1], 1e-4);
    }

    @Test
    void distanceFadesToZeroAtTheRadius() {
        float near = gains(new double[] {0, 0, 0, 0}, new double[] {0, 0, 2}, 20)[0];
        float mid = gains(new double[] {0, 0, 0, 0}, new double[] {0, 0, 10}, 20)[0];
        float edge = gains(new double[] {0, 0, 0, 0}, new double[] {0, 0, 20}, 20)[0];
        assertTrue(near > mid && mid > edge);
        assertEquals(0f, edge, 1e-6);
        float above = gains(new double[] {0, 0, 0, 0}, new double[] {0, 15, 0}, 20)[0];
        assertTrue(above > 0 && above < near, "vertical distance counts too");
    }

    @Test
    void speakerBuffersBeforePlayingAndStopsAfterEnd() {
        RemoteSpeaker speaker = new RemoteSpeaker(1);
        byte[] frame = new OpusCodec.Encoder(16000).encode(sine(0));
        speaker.offer(frame, AudioCategory.GROUP, 0, 0);
        assertFalse(speaker.next(), "one frame is not enough to start");
        speaker.offer(frame, AudioCategory.GROUP, 0, 0);
        assertTrue(speaker.next());
        assertTrue(speaker.next());
        assertTrue(speaker.next(), "short gap is concealed");
        speaker.end();
        assertFalse(speaker.next(), "end packet stops playback");
    }

    @Test
    void speakerSkipsAheadWhenFarBehind() {
        RemoteSpeaker speaker = new RemoteSpeaker(1);
        byte[] frame = new OpusCodec.Encoder(16000).encode(sine(0));
        for (int i = 0; i < RemoteSpeaker.MAX_DEPTH + 1; i++) {
            speaker.offer(frame, AudioCategory.GROUP, 0, 0);
        }
        assertEquals(RemoteSpeaker.START_DEPTH, speaker.buffered());
    }

    @Test
    void opusRoundTripKeepsTheSignal() {
        OpusCodec.Encoder encoder = new OpusCodec.Encoder(16000);
        OpusCodec.Decoder decoder = new OpusCodec.Decoder();
        short[] out = new short[BvcProtocol.FRAME_SAMPLES];
        for (int i = 0; i < 5; i++) {
            decoder.decode(encoder.encode(sine(i)), out);
        }
        assertTrue(VoiceActivity.levelDb(out) > -20, "decoded level " + VoiceActivity.levelDb(out));
        decoder.decode(new byte[] {1, 2, 3}, out); // garbage must not throw
    }

    @Test
    void mixerDucksLowerPriorityAndRespectsLocalMute() {
        OpusCodec.Encoder encoder = new OpusCodec.Encoder(24000);
        RemoteSpeaker boss = new RemoteSpeaker(1);
        RemoteSpeaker soldier = new RemoteSpeaker(2);
        boss.identify(new UUID(0, 1), "boss");
        soldier.identify(new UUID(0, 2), "soldier");
        for (int i = 0; i < 4; i++) {
            soldier.offer(encoder.encode(sine(i)), AudioCategory.GROUP, 0, 0);
        }
        short[] out = new short[BvcProtocol.FRAME_SAMPLES * 2];
        VoiceMixer mixer = new VoiceMixer();
        mixer.mix(List.of(soldier), new Positions(), 0, 20, 1f, uuid -> 1f, out);
        mixer.mix(List.of(soldier), new Positions(), 0, 20, 1f, uuid -> 1f, out);
        float alone = peak(out);

        RemoteSpeaker quietBoss = boss;
        // Own encoder: a shared one would carry the soldier's tone into the "silent" frames.
        OpusCodec.Encoder bossEncoder = new OpusCodec.Encoder(24000);
        for (int i = 0; i < 4; i++) {
            quietBoss.offer(bossEncoder.encode(silence()), AudioCategory.GROUP, 100, 0);
        }
        mixer.mix(List.of(soldier, boss), new Positions(), 0, 20, 1f, uuid -> 1f, out);
        float ducked = peak(out);
        assertTrue(ducked < alone / 3, "20 dB ducking: " + alone + " -> " + ducked);

        for (int i = 0; i < 4; i++) {
            soldier.offer(encoder.encode(sine(i)), AudioCategory.GROUP, 0, 0);
        }
        mixer.mix(List.of(soldier), new Positions(), 0, 20, 1f, uuid -> 0f, out);
        assertEquals(0f, peak(out), "locally muted player is silent");
    }

    @Test
    void voiceActivityHasHangover() {
        VoiceActivity activity = new VoiceActivity();
        assertFalse(activity.update(-60, -45));
        assertTrue(activity.update(-30, -45));
        for (int i = 0; i < VoiceActivity.HANGOVER_FRAMES; i++) {
            assertTrue(activity.update(-80, -45));
        }
        assertFalse(activity.update(-80, -45));
        assertEquals(-90f, VoiceActivity.levelDb(silence()));
    }

    static short[] sine(int frameIndex) {
        short[] frame = new short[BvcProtocol.FRAME_SAMPLES];
        for (int i = 0; i < frame.length; i++) {
            double t = (frameIndex * frame.length + i) / (double) BvcProtocol.SAMPLE_RATE;
            frame[i] = (short) (8000 * Math.sin(2 * Math.PI * 440 * t));
        }
        return frame;
    }

    static short[] silence() {
        return new short[BvcProtocol.FRAME_SAMPLES];
    }

    static float peak(short[] samples) {
        float peak = 0;
        for (short s : samples) {
            peak = Math.max(peak, Math.abs(s));
        }
        return peak;
    }
}
