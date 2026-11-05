package top.dext.bvc.protocol.data;

/**
 * Who can hear whom inside a voice zone. Decided by the server, never by the player.
 */
public enum VoiceMode {
    /** Same world, within the zone radius. */
    PROXIMITY(0),
    /** Only teammates, at any distance. */
    TEAM(1),
    /** Everyone in the zone (by default the whole sub-server), at any distance. */
    FREE(2),
    /** Teammates at any distance plus everyone else within the radius. */
    TEAM_PROXIMITY(3),
    /** No voice at all in this zone. */
    DISABLED(4);

    private static final VoiceMode[] BY_ID = new VoiceMode[5];

    static {
        for (VoiceMode mode : values()) {
            BY_ID[mode.id] = mode;
        }
    }

    private final int id;

    VoiceMode(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public boolean usesProximity() {
        return this == PROXIMITY || this == TEAM_PROXIMITY;
    }

    public boolean usesTeams() {
        return this == TEAM || this == TEAM_PROXIMITY;
    }

    /** Unknown ids (from a newer peer) degrade to {@link #DISABLED}. */
    public static VoiceMode byId(int id) {
        return id >= 0 && id < BY_ID.length && BY_ID[id] != null ? BY_ID[id] : DISABLED;
    }
}
