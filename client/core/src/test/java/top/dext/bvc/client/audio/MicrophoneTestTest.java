package top.dext.bvc.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import top.dext.bvc.protocol.BvcProtocol;

/**
 * The test only listens to frames the voice capture feeds it; it never opens the microphone.
 */
class MicrophoneTestTest {

    private static final class Devices implements AudioDevices {
        final AtomicInteger inputsOpened = new AtomicInteger();
        final AtomicInteger outputFrames = new AtomicInteger();

        @Override
        public List<String> inputDevices() {
            return List.of("mic");
        }

        @Override
        public List<String> outputDevices() {
            return List.of("speakers");
        }

        @Override
        public AudioInput openInput(String name) {
            inputsOpened.incrementAndGet();
            throw new AssertionError("the test must not open the microphone");
        }

        @Override
        public AudioOutput openOutput(String name) {
            return new AudioOutput() {
                @Override
                public void write(short[] stereoFrame) {
                    outputFrames.incrementAndGet();
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static short[] tone() {
        short[] frame = new short[BvcProtocol.FRAME_SAMPLES];
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (short) (8000 * Math.sin(i * 0.06));
        }
        return frame;
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long until = System.currentTimeMillis() + 2000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
    }

    @Test
    void soundIsDetectedAndLoopedBackWithoutOpeningTheMicrophone() throws Exception {
        Devices devices = new Devices();
        MicrophoneTest test = new MicrophoneTest(devices);
        test.start("speakers", true);
        assertEquals(MicrophoneTest.State.STARTING, test.state());
        short[] frame = tone();
        for (int i = 0; i < 10; i++) {
            test.feed(frame, VoiceActivity.levelDb(frame));
            Thread.sleep(20);
        }
        assertEquals(MicrophoneTest.State.WORKING, test.state());
        assertTrue(test.levelDb() > -30, "level " + test.levelDb());
        waitFor(() -> devices.outputFrames.get() > 3);
        assertTrue(devices.outputFrames.get() > 3, "heard yourself");
        test.stop();
        assertEquals(MicrophoneTest.State.IDLE, test.state());
        assertEquals(0, devices.inputsOpened.get());
    }

    @Test
    void silenceAndFailureAreReported() throws Exception {
        MicrophoneTest test = new MicrophoneTest(new Devices());
        test.start("speakers", false);
        short[] quiet = new short[BvcProtocol.FRAME_SAMPLES];
        test.feed(quiet, -90f);
        assertEquals(MicrophoneTest.State.STARTING, test.state(), "not silent yet right after starting");
        Thread.sleep(MicrophoneTest.SILENT_AFTER_MILLIS + 100);
        test.feed(quiet, -90f);
        assertEquals(MicrophoneTest.State.SILENT, test.state());

        test.inputFailed("Microphone unavailable: busy");
        assertEquals(MicrophoneTest.State.FAILED, test.state());
        assertTrue(test.error().contains("busy"));
        test.stop();
        test.inputFailed("ignored when not running");
        assertEquals(MicrophoneTest.State.IDLE, test.state());
    }
}
