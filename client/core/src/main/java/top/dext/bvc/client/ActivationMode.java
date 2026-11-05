package top.dext.bvc.client;

/**
 * How the player opens their microphone. The server can force {@link #PUSH_TO_TALK}.
 */
public enum ActivationMode {
    /** Talk while the key is held. */
    PUSH_TO_TALK,
    /** Talk when the microphone picks up voice. */
    VOICE,
    /** Press once to start talking, again to stop. */
    TOGGLE
}
