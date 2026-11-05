package top.dext.bvc.api.server;

/**
 * How players may open their microphone in a zone.
 */
public enum Activation {
    /** Push-to-talk or voice activation, the player picks. */
    ANY,
    /** Push-to-talk only (enforced by the client mod). */
    PTT_ONLY
}
