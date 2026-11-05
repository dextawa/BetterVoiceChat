package top.dext.bvc.proxy.routing;

import top.dext.bvc.proxy.session.VoiceSession;

/**
 * Receives every frame the router accepted: the recorder and the "who heard whom" log for
 * reports plug in here. Called on network threads; implementations must hand off heavy work.
 */
public interface AudioSink {

    AudioSink NONE = new AudioSink() {
    };

    /** A frame passed all checks and was forwarded (or would have been, with no listeners). */
    default void onAudio(String server, VoiceSession speaker, int sequence, byte[] opus, long now) {
    }

    /** The speaker stopped (push-to-talk released or voice activation ended). */
    default void onAudioEnd(String server, VoiceSession speaker, long now) {
    }

    /** {@code listener} received a frame from {@code speaker}. */
    default void onHeard(VoiceSession listener, VoiceSession speaker, long now) {
    }
}
