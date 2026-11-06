package top.dext.bvc.client.audio;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import top.dext.bvc.protocol.BvcProtocol;

/**
 * "Test microphone" in the settings screen. It never opens the microphone itself: the voice
 * client's own capture feeds it every frame ({@link #feed}), so voice keeps working during a
 * test and the device is only ever opened once. It shows the live level and can play the
 * microphone back on the speakers so the player hears themselves.
 */
public final class MicrophoneTest {

    public enum State {
        /** Not running. */
        IDLE,
        /** Running, no sound heard yet: the player should say something. */
        STARTING,
        /** Frames arrive and carry sound. */
        WORKING,
        /** Frames arrive but have been silent for a while: wrong device, muted in the OS, unplugged... */
        SILENT,
        /** The microphone could not be opened. */
        FAILED
    }

    /** Silence for this long counts as {@link State#SILENT}. */
    static final long SILENT_AFTER_MILLIS = 3000;
    /** Below this level a frame counts as silence: room noise on real headsets sits around -75 dB, speech -40..-15 dB. */
    static final float SILENCE_DB = -60f;

    private final AudioDevices devices;
    private volatile boolean running;
    private volatile State state = State.IDLE;
    private volatile float levelDb = -90f;
    private volatile float peakDb = -90f;
    private volatile String error = "";
    private volatile long startedAt;
    private volatile long lastSound;
    /** Frames waiting to be played back; small, so loopback latency stays low. */
    private final BlockingQueue<short[]> loopback = new ArrayBlockingQueue<short[]>(5);
    private volatile Thread playback;

    public MicrophoneTest(AudioDevices devices) {
        this.devices = devices;
    }

    public State state() {
        return state;
    }

    public boolean isRunning() {
        return running;
    }

    /** Level of the latest frame (after gain), -90 .. 0 dB. */
    public float levelDb() {
        return levelDb;
    }

    /** Highest level since the test started. */
    public float peakDb() {
        return peakDb;
    }

    /** Why it {@link State#FAILED}, or empty. */
    public String error() {
        return error;
    }

    /** Starts (or restarts) the test. With {@code loopbackOn}, frames are played on {@code outputDevice}. */
    public synchronized void start(final String outputDevice, boolean loopbackOn) {
        stop();
        running = true;
        state = State.STARTING;
        levelDb = -90f;
        peakDb = -90f;
        error = "";
        startedAt = System.currentTimeMillis();
        lastSound = 0;
        loopback.clear();
        if (loopbackOn) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    playLoop(outputDevice);
                }
            }, "BVC-MicTest");
            thread.setDaemon(true);
            playback = thread;
            thread.start();
        }
    }

    public synchronized void stop() {
        running = false;
        Thread thread = playback;
        playback = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        loopback.clear();
        state = State.IDLE;
        levelDb = -90f;
    }

    /** One captured frame (gain already applied), from the voice client's capture thread. */
    public void feed(short[] frame, float level) {
        if (!running) {
            return;
        }
        levelDb = level;
        peakDb = Math.max(peakDb, level);
        long now = System.currentTimeMillis();
        if (level > SILENCE_DB) {
            lastSound = now;
            state = State.WORKING;
        } else if (now - Math.max(lastSound, startedAt) > SILENT_AFTER_MILLIS) {
            state = State.SILENT;
        }
        if (playback != null && !loopback.offer(frame.clone())) {
            loopback.poll();
            loopback.offer(frame.clone());
        }
    }

    /** The capture could not open the microphone. */
    public void inputFailed(String message) {
        if (running) {
            error = message == null ? "" : message;
            state = State.FAILED;
            levelDb = -90f;
        }
    }

    private void playLoop(String outputDevice) {
        AudioDevices.AudioOutput output;
        try {
            output = devices.openOutput(outputDevice);
        } catch (IOException e) {
            return; // the level meter still works without hearing yourself
        }
        short[] stereo = new short[BvcProtocol.FRAME_SAMPLES * 2];
        try {
            while (playback == Thread.currentThread()) {
                short[] frame = loopback.poll(200, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    continue;
                }
                for (int i = 0; i < frame.length; i++) {
                    stereo[2 * i] = frame[i];
                    stereo[2 * i + 1] = frame[i];
                }
                output.write(stereo);
            }
        } catch (InterruptedException | IOException e) {
            // stopped, or the speakers went away
        } finally {
            output.close();
        }
    }
}
