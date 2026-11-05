package top.dext.bvc.api.server;

/**
 * Who can hear whom inside a voice zone. Decided by the server, never by the player.
 */
public enum VoiceMode {
    /** Same world, within the zone radius. */
    PROXIMITY,
    /** Only teammates, at any distance. */
    TEAM,
    /** Everyone in the zone (the whole sub-server unless a room is used), at any distance. */
    FREE,
    /** Teammates at any distance plus everyone else within the radius. */
    TEAM_PROXIMITY,
    /** No voice in this zone. */
    DISABLED
}
