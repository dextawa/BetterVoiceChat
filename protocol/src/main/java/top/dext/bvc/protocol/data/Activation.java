package top.dext.bvc.protocol.data;

/**
 * How players are allowed to open their microphone in a zone.
 * <p>
 * {@link #PTT_ONLY} is enforced by the client: the server cannot tell a key press from
 * voice activation, so a modified client can bypass it. Mutes and reports are the backstop.
 */
public enum Activation {
    /** Push-to-talk or voice activation, the player picks. */
    ANY(0),
    /** Push-to-talk only. */
    PTT_ONLY(1);

    private final int id;

    Activation(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static Activation byId(int id) {
        return id == 0 ? ANY : PTT_ONLY;
    }
}
