package top.dext.bvc.protocol.data;

/**
 * Why a listener receives a frame. Decides how the client plays it.
 */
public enum AudioCategory {
    /** Same group (team or free-for-all zone): full volume, no positioning. */
    GROUP(0),
    /** In range: 3D positioned and attenuated by distance. */
    PROXIMITY(1),
    /** Zone-wide broadcast from a privileged speaker: full volume, no positioning. */
    BROADCAST(2);

    private final int id;

    AudioCategory(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static AudioCategory byId(int id) {
        switch (id) {
            case 1:
                return PROXIMITY;
            case 2:
                return BROADCAST;
            default:
                return GROUP;
        }
    }
}
