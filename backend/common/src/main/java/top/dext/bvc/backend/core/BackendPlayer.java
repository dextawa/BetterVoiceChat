package top.dext.bvc.backend.core;

import java.util.UUID;

/**
 * An online player as the platform sees it; outgoing plugin messages ride on its connection.
 */
public interface BackendPlayer {

    UUID uuid();

    String name();

    boolean isOnline();

    /** Whether the other end registered {@code channel}; platforms that cannot tell return true. */
    boolean listensOn(String channel);

    /** Called from the planner thread. */
    void sendPluginMessage(String channel, byte[] data);
}
