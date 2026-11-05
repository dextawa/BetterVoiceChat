package top.dext.bvc.proxy.platform;

import java.net.SocketAddress;

/**
 * An online player as seen by the proxy platform. Implementations must be thread-safe.
 */
public interface PlatformPlayer extends CommandSource {

    /** Current sub-server name, or {@code null} while not connected to one. */
    String currentServer();

    /** Sends a plugin message to the client. */
    void sendPluginMessage(String channel, byte[] data);

    /**
     * Sends a plugin message to the sub-server this player is connected to.
     *
     * @return false if the player is not connected to a sub-server
     */
    boolean sendToBackend(String channel, byte[] data);

    /** Text with {@code &} colour codes; {@code \n} for new lines. */
    void disconnect(String text);

    boolean isOnline();

    SocketAddress remoteAddress();
}
