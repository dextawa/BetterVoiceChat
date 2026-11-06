package top.dext.bvc.proxy.recording;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Groups one speaker's frames into clips (utterances) and turns a clip into Ogg Opus, padding
 * short gaps with silence so playback keeps the original timing.
 */
public final class Clips {

    /** A gap longer than this starts a new clip. */
    static final long CLIP_GAP_MILLIS = 1500;
    private static final long FRAME_MILLIS = 20;

    public record TimedFrame(long at, byte[] opus, int flags) {
    }

    public record Clip(UUID speaker, String name, long from, long to, boolean heard, List<TimedFrame> frames) {
    }

    private Clips() {
    }

    /** {@code frames} of one speaker, in time order. */
    public static List<Clip> group(UUID speaker, String name, List<TimedFrame> frames) {
        List<Clip> clips = new ArrayList<>();
        List<TimedFrame> current = new ArrayList<>();
        for (TimedFrame frame : frames) {
            if (!current.isEmpty() && frame.at() - current.get(current.size() - 1).at() > CLIP_GAP_MILLIS) {
                clips.add(toClip(speaker, name, current));
                current = new ArrayList<>();
            }
            current.add(frame);
        }
        if (!current.isEmpty()) {
            clips.add(toClip(speaker, name, current));
        }
        return clips;
    }

    private static Clip toClip(UUID speaker, String name, List<TimedFrame> frames) {
        boolean heard = false;
        for (TimedFrame frame : frames) {
            heard |= (frame.flags() & SegmentFile.FLAG_HEARD) != 0;
        }
        return new Clip(speaker, name, frames.get(0).at(), frames.get(frames.size() - 1).at() + FRAME_MILLIS, heard,
                List.copyOf(frames));
    }

    /** Frames arriving later than this behind schedule mark a real pause, filled with silence. */
    static final long JITTER_MILLIS = 60;

    /**
     * Ogg Opus of the frames. Frames are played back to back like a client's jitter buffer
     * would; only a pause longer than {@link #JITTER_MILLIS} (the speaker really stopped, or
     * frames were lost) is filled with silence, so network jitter does not stretch the clip.
     */
    public static byte[] toOgg(List<TimedFrame> frames, int serial) {
        OggOpus ogg = new OggOpus(serial);
        if (frames.isEmpty()) {
            return ogg.finish();
        }
        long start = frames.get(0).at();
        long written = 0;
        for (TimedFrame frame : frames) {
            long due = start + written * FRAME_MILLIS;
            long late = frame.at() - due;
            if (late > JITTER_MILLIS) {
                long gap = late / FRAME_MILLIS;
                ogg.silence(gap);
                written += gap;
            }
            ogg.frame(frame.opus());
            written++;
        }
        return ogg.finish();
    }
}
