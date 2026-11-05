package top.dext.bvc.client.audio;

import java.io.IOException;
import java.util.List;

/**
 * Opens microphones and speakers. All audio is 48 kHz, 16-bit signed; input mono, output stereo.
 */
public interface AudioDevices {

    List<String> inputDevices();

    List<String> outputDevices();

    /** {@code name} empty = system default. */
    AudioInput openInput(String name) throws IOException;

    /** {@code name} empty = system default. */
    AudioOutput openOutput(String name) throws IOException;

    interface AudioInput extends AutoCloseable {
        /** Blocks until {@code frame} is filled with one 20 ms frame (960 samples). */
        void read(short[] frame) throws IOException;

        @Override
        void close();
    }

    interface AudioOutput extends AutoCloseable {
        /** Blocks while the device buffer is full; this is what paces playback. 1920 interleaved samples. */
        void write(short[] stereoFrame) throws IOException;

        @Override
        void close();
    }
}
