package top.dext.bvc.client.audio;

import java.util.Arrays;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;
import top.dext.bvc.protocol.BvcProtocol;

/**
 * Opus via Concentus (pure Java, no native library), 48 kHz mono 20 ms frames.
 */
public final class OpusCodec {

    private OpusCodec() {
    }

    public static final class Encoder {
        private final OpusEncoder encoder;
        private final byte[] buffer = new byte[BvcProtocol.MAX_OPUS_PACKET];
        private int bitrate;

        public Encoder(int bitrate) {
            try {
                encoder = new OpusEncoder(BvcProtocol.SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            } catch (OpusException e) {
                throw new IllegalStateException(e);
            }
            encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
            encoder.setComplexity(5);
            setBitrate(bitrate);
        }

        public void setBitrate(int value) {
            if (value != bitrate) {
                bitrate = value;
                encoder.setBitrate(value);
            }
        }

        /** Encodes one 960-sample frame. */
        public byte[] encode(short[] frame) {
            try {
                int length = encoder.encode(frame, 0, BvcProtocol.FRAME_SAMPLES, buffer, 0, buffer.length);
                byte[] out = new byte[length];
                System.arraycopy(buffer, 0, out, 0, length);
                return out;
            } catch (OpusException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public static final class Decoder {
        private OpusDecoder decoder;

        public Decoder() {
            reset();
        }

        public void reset() {
            try {
                decoder = new OpusDecoder(BvcProtocol.SAMPLE_RATE, 1);
            } catch (OpusException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Decodes into {@code pcm} (960 samples). {@code opus == null} conceals a missing frame. */
        public void decode(byte[] opus, short[] pcm) {
            try {
                if (opus == null) {
                    decoder.decode(null, 0, 0, pcm, 0, BvcProtocol.FRAME_SAMPLES, false);
                } else {
                    decoder.decode(opus, 0, opus.length, pcm, 0, BvcProtocol.FRAME_SAMPLES, false);
                }
            } catch (OpusException | RuntimeException e) {
                // A corrupt frame must not kill playback: play silence and start fresh.
                Arrays.fill(pcm, (short) 0);
                reset();
            }
        }
    }
}
