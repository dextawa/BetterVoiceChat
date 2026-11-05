package top.dext.bvc.client.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.data.AudioCategory;

/**
 * Mixes every speaker into one stereo frame: distance and direction for proximity voices,
 * per-player volume, ducking of lower-priority voices, and a limiter instead of hard clipping.
 * Used only by the playback thread.
 */
public final class VoiceMixer {

    /** Proximity voices whose position this client does not know (not rendered, vanished...). */
    static final float UNKNOWN_POSITION_GAIN = 0.5f;
    /** Panning never goes fully to one ear; that sounds unnatural on headphones. */
    private static final double PAN_WIDTH = 0.8;

    public interface VolumeSource {
        /** 0..2, 1 = normal, 0 = locally muted. */
        float volume(UUID player);
    }

    private final float[] left = new float[BvcProtocol.FRAME_SAMPLES];
    private final float[] right = new float[BvcProtocol.FRAME_SAMPLES];
    private final float[] gains = new float[2];
    private final List<RemoteSpeaker> active = new ArrayList<RemoteSpeaker>();

    /**
     * @param out 1920 interleaved stereo samples
     * @return how many speakers were audible
     */
    public int mix(Iterable<RemoteSpeaker> speakers, Positions positions, float radius, float duckingDb,
                   float masterVolume, VolumeSource volumes, short[] out) {
        active.clear();
        int topPriority = -1;
        for (RemoteSpeaker speaker : speakers) {
            if (speaker.next()) {
                active.add(speaker);
                topPriority = Math.max(topPriority, speaker.priority());
            }
        }
        if (active.isEmpty()) {
            Arrays.fill(out, (short) 0);
            return 0;
        }

        Arrays.fill(left, 0f);
        Arrays.fill(right, 0f);
        float ducked = (float) Math.pow(10, -duckingDb / 20.0);
        double[] listener = positions.listener();
        for (RemoteSpeaker speaker : active) {
            float volume = masterVolume * volumes.volume(speaker.uuid());
            if (speaker.priority() < topPriority) {
                volume *= ducked;
            }
            if (volume <= 0f) {
                continue;
            }
            if (speaker.category() == AudioCategory.PROXIMITY) {
                double[] source = positions.position(speaker.uuid());
                if (listener != null && source != null) {
                    spatialGains(listener, source, radius, gains);
                } else {
                    gains[0] = UNKNOWN_POSITION_GAIN;
                    gains[1] = UNKNOWN_POSITION_GAIN;
                }
            } else {
                gains[0] = 1f;
                gains[1] = 1f;
            }
            float gl = gains[0] * volume;
            float gr = gains[1] * volume;
            short[] pcm = speaker.pcm();
            for (int i = 0; i < pcm.length; i++) {
                left[i] += pcm[i] * gl;
                right[i] += pcm[i] * gr;
            }
        }

        float peak = 0f;
        for (int i = 0; i < left.length; i++) {
            peak = Math.max(peak, Math.max(Math.abs(left[i]), Math.abs(right[i])));
        }
        float scale = peak > Short.MAX_VALUE ? Short.MAX_VALUE / peak : 1f;
        for (int i = 0; i < left.length; i++) {
            out[2 * i] = (short) (left[i] * scale);
            out[2 * i + 1] = (short) (right[i] * scale);
        }
        return active.size();
    }

    /**
     * Left/right gain for a source heard by a listener: linear fade to zero at {@code radius},
     * constant-power panning by direction.
     *
     * @param listener {x, y, z, yaw}; yaw 0 faces south (+z), 90 faces west (-x)
     * @param source   {x, y, z}
     */
    static void spatialGains(double[] listener, double[] source, float radius, float[] out) {
        double dx = source[0] - listener[0];
        double dy = source[1] - listener[1];
        double dz = source[2] - listener[2];
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float attenuation = radius <= 0 ? 1f : (float) Math.max(0, 1 - distance / radius);

        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double pan = 0;
        if (horizontal > 1e-3) {
            double yaw = Math.toRadians(listener[3]);
            // Right-hand direction of a player facing yaw is (-cos yaw, -sin yaw) in (x, z).
            pan = (dx * -Math.cos(yaw) + dz * -Math.sin(yaw)) / horizontal;
        }
        double angle = (pan * PAN_WIDTH + 1) * Math.PI / 4;
        out[0] = attenuation * (float) Math.min(1, Math.sqrt(2) * Math.cos(angle));
        out[1] = attenuation * (float) Math.min(1, Math.sqrt(2) * Math.sin(angle));
    }
}
