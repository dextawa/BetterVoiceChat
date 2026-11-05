package top.dext.bvc.client.audio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

import top.dext.bvc.protocol.BvcProtocol;

/**
 * Microphone and speakers through Java Sound. It ships with every desktop Java, so the same
 * code works on 1.8 (LWJGL 2) and modern versions (LWJGL 3) alike; BVC mixes and positions
 * audio itself and only hands the device one stereo stream.
 */
public final class JavaSoundDevices implements AudioDevices {

    private static final AudioFormat INPUT = new AudioFormat(BvcProtocol.SAMPLE_RATE, 16, 1, true, false);
    private static final AudioFormat OUTPUT = new AudioFormat(BvcProtocol.SAMPLE_RATE, 16, 2, true, false);
    /** Output buffer: 4 frames (80 ms). Bigger = more latency, smaller = crackles on busy machines. */
    private static final int OUTPUT_BUFFER_BYTES = BvcProtocol.FRAME_SAMPLES * 2 * 2 * 4;

    @Override
    public List<String> inputDevices() {
        return devices(new DataLine.Info(TargetDataLine.class, INPUT));
    }

    @Override
    public List<String> outputDevices() {
        return devices(new DataLine.Info(SourceDataLine.class, OUTPUT));
    }

    private static List<String> devices(DataLine.Info info) {
        List<String> names = new ArrayList<String>();
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            try {
                if (AudioSystem.getMixer(mixerInfo).isLineSupported(info)) {
                    names.add(mixerInfo.getName());
                }
            } catch (RuntimeException ignored) {
                // broken drivers: skip
            }
        }
        return names;
    }

    private static Mixer.Info find(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (info.getName().equals(name)) {
                return info;
            }
        }
        return null;
    }

    @Override
    public AudioInput openInput(String name) throws IOException {
        try {
            Mixer.Info mixer = find(name);
            TargetDataLine line = mixer != null ? AudioSystem.getTargetDataLine(INPUT, mixer) : AudioSystem.getTargetDataLine(INPUT);
            line.open(INPUT, BvcProtocol.FRAME_SAMPLES * 2 * 4);
            line.start();
            return new Input(line);
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            throw new IOException("Microphone unavailable: " + e.getMessage(), e);
        }
    }

    @Override
    public AudioOutput openOutput(String name) throws IOException {
        try {
            Mixer.Info mixer = find(name);
            SourceDataLine line = mixer != null ? AudioSystem.getSourceDataLine(OUTPUT, mixer) : AudioSystem.getSourceDataLine(OUTPUT);
            line.open(OUTPUT, OUTPUT_BUFFER_BYTES);
            line.start();
            return new Output(line);
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            throw new IOException("Speakers unavailable: " + e.getMessage(), e);
        }
    }

    private static final class Input implements AudioInput {
        private final TargetDataLine line;
        private final byte[] buffer = new byte[BvcProtocol.FRAME_SAMPLES * 2];

        Input(TargetDataLine line) {
            this.line = line;
        }

        @Override
        public void read(short[] frame) throws IOException {
            int filled = 0;
            while (filled < buffer.length) {
                int read = line.read(buffer, filled, buffer.length - filled);
                if (read <= 0) {
                    if (!line.isOpen()) {
                        throw new IOException("Microphone closed");
                    }
                    continue;
                }
                filled += read;
            }
            for (int i = 0; i < frame.length; i++) {
                frame[i] = (short) ((buffer[2 * i] & 0xFF) | (buffer[2 * i + 1] << 8));
            }
        }

        @Override
        public void close() {
            line.stop();
            line.close();
        }
    }

    private static final class Output implements AudioOutput {
        private final SourceDataLine line;
        private final byte[] buffer = new byte[BvcProtocol.FRAME_SAMPLES * 2 * 2];

        Output(SourceDataLine line) {
            this.line = line;
        }

        @Override
        public void write(short[] stereoFrame) throws IOException {
            for (int i = 0; i < stereoFrame.length; i++) {
                buffer[2 * i] = (byte) stereoFrame[i];
                buffer[2 * i + 1] = (byte) (stereoFrame[i] >> 8);
            }
            int written = 0;
            while (written < buffer.length) {
                int n = line.write(buffer, written, buffer.length - written);
                if (n <= 0 && !line.isOpen()) {
                    throw new IOException("Speakers closed");
                }
                written += Math.max(0, n);
            }
        }

        @Override
        public void close() {
            line.stop();
            line.close();
        }
    }
}
