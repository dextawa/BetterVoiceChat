package top.dext.bvc.client.audio;

import java.util.ArrayDeque;
import java.util.UUID;

import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.data.AudioCategory;

/**
 * One remote player we are hearing: their incoming frames and decoder.
 * <p>
 * The stream is TCP, so frames never get lost or reordered, but they can arrive in bursts. The
 * buffer waits for {@link #START_DEPTH} frames before it starts playing, conceals short gaps,
 * and drops old frames when it falls too far behind so latency stays bounded.
 */
public final class RemoteSpeaker {

    static final int START_DEPTH = 2;
    static final int MAX_DEPTH = 10;
    /** Concealed frames before we decide the speaker went quiet without an end packet. */
    static final int MAX_CONCEALED = 5;

    private final int id;
    private volatile UUID uuid;
    private volatile String name;
    private volatile AudioCategory category = AudioCategory.GROUP;
    private volatile int priority;
    private volatile long lastFrameAt;

    private final ArrayDeque<byte[]> frames = new ArrayDeque<byte[]>();
    private boolean ended;
    private boolean playing;
    private int concealed;
    private final OpusCodec.Decoder decoder = new OpusCodec.Decoder();
    /** Decoded frame, owned by the playback thread. */
    private final short[] pcm = new short[BvcProtocol.FRAME_SAMPLES];

    public RemoteSpeaker(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void identify(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public AudioCategory category() {
        return category;
    }

    public int priority() {
        return priority;
    }

    public boolean isSpeaking(long now) {
        return now - lastFrameAt < 300;
    }

    // ------------------------------------------------------------------ network side

    public synchronized void offer(byte[] opus, AudioCategory category, int priority, long now) {
        this.category = category;
        this.priority = priority;
        this.lastFrameAt = now;
        ended = false;
        frames.addLast(opus);
        if (frames.size() > MAX_DEPTH) {
            // Way behind: skip ahead so the delay does not keep growing.
            while (frames.size() > START_DEPTH) {
                frames.pollFirst();
            }
        }
    }

    public synchronized void end() {
        ended = true;
    }

    // ------------------------------------------------------------------ playback side

    /**
     * Decodes the next 20 ms of this speaker into {@link #pcm()}.
     *
     * @return false if the speaker is silent this frame
     */
    public boolean next() {
        byte[] frame;
        boolean conceal;
        synchronized (this) {
            if (!playing) {
                if (frames.size() >= START_DEPTH || (ended && !frames.isEmpty())) {
                    playing = true;
                    concealed = 0;
                } else {
                    return false;
                }
            }
            frame = frames.pollFirst();
            if (frame != null) {
                concealed = 0;
                conceal = false;
            } else if (ended || ++concealed > MAX_CONCEALED) {
                playing = false;
                ended = false;
                frame = null;
                conceal = false;
            } else {
                conceal = true;
            }
        }
        if (frame == null && !conceal) {
            decoder.reset();
            return false;
        }
        decoder.decode(frame, pcm);
        return true;
    }

    /** The frame decoded by the last successful {@link #next()}. */
    public short[] pcm() {
        return pcm;
    }

    synchronized int buffered() {
        return frames.size();
    }
}
