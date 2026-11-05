package top.dext.bvc.protocol.data;

/**
 * What kind of mute currently silences a player.
 */
public enum MuteKind {
    /** The player personally is muted (network-wide or on one sub-server). */
    PLAYER(0),
    /** Everyone on the current sub-server is muted. */
    SERVER_WIDE(1),
    /** Everyone on the whole network is muted. */
    GLOBAL_WIDE(2);

    private final int id;

    MuteKind(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static MuteKind byId(int id) {
        switch (id) {
            case 1:
                return SERVER_WIDE;
            case 2:
                return GLOBAL_WIDE;
            default:
                return PLAYER;
        }
    }
}
