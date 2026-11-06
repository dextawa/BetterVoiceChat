package top.dext.bvc.backend.voice;

/**
 * How players in spectator mode are treated (typically dead players in minigames).
 */
public enum SpectatorPolicy {
    /** Like everyone else. */
    SAME,
    /** They hear as usual but cannot speak. */
    LISTEN_ONLY,
    /** They only hear and talk to other spectators (at any distance), so the dead cannot call out. */
    SEPARATE
}
