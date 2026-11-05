package top.dext.bvc.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import top.dext.bvc.client.audio.AudioDevices;
import top.dext.bvc.protocol.BvcProtocol;

/**
 * Microphone that plays a 440 Hz tone while {@link #speaking} is set, and speakers that record
 * the per-frame peak of each channel. Both are paced at real time like real devices.
 */
final class FakeAudio implements AudioDevices {

    volatile boolean speaking;
    /** {left peak, right peak} of the most recent output frame. */
    final AtomicReference<float[]> lastPeaks = new AtomicReference<>(new float[2]);
    private final List<float[]> history = new ArrayList<>();

    @Override
    public List<String> inputDevices() {
        return List.of("fake mic");
    }

    @Override
    public List<String> outputDevices() {
        return List.of("fake speakers");
    }

    @Override
    public AudioInput openInput(String name) {
        return new AudioInput() {
            private int frameIndex;

            @Override
            public void read(short[] frame) throws IOException {
                pace();
                for (int i = 0; i < frame.length; i++) {
                    double t = (frameIndex * frame.length + i) / (double) BvcProtocol.SAMPLE_RATE;
                    frame[i] = speaking ? (short) (10000 * Math.sin(2 * Math.PI * 440 * t)) : 0;
                }
                frameIndex++;
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public AudioOutput openOutput(String name) {
        return new AudioOutput() {
            @Override
            public void write(short[] stereoFrame) throws IOException {
                float left = 0;
                float right = 0;
                for (int i = 0; i < stereoFrame.length; i += 2) {
                    left = Math.max(left, Math.abs(stereoFrame[i]));
                    right = Math.max(right, Math.abs(stereoFrame[i + 1]));
                }
                float[] peaks = {left, right};
                lastPeaks.set(peaks);
                synchronized (history) {
                    history.add(peaks);
                    if (history.size() > 200) {
                        history.remove(0);
                    }
                }
                pace();
            }

            @Override
            public void close() {
            }
        };
    }

    /** Max {left, right} peaks over the last {@code frames} output frames. */
    float[] recentPeaks(int frames) {
        float left = 0;
        float right = 0;
        synchronized (history) {
            for (int i = Math.max(0, history.size() - frames); i < history.size(); i++) {
                left = Math.max(left, history.get(i)[0]);
                right = Math.max(right, history.get(i)[1]);
            }
        }
        return new float[] {left, right};
    }

    private static void pace() throws IOException {
        try {
            Thread.sleep(BvcProtocol.FRAME_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }
}
