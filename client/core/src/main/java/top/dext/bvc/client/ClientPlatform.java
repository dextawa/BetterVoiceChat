package top.dext.bvc.client;

/**
 * Everything the client core needs from a Minecraft version / loader adapter. Methods may be
 * called from any thread; implementations hop onto the game thread themselves if needed.
 */
public interface ClientPlatform {

    /** Sends a {@code bvc:voice} plugin message to the server (the proxy). */
    void sendPluginMessage(byte[] payload);

    String minecraftVersion();

    /** "fabric", "forge", "neoforge"... */
    String loader();

    String modVersion();

    /** Minecraft protocol number, put into the fake handshake of the stream connection. */
    int minecraftProtocol();

    /** Shows a line in chat. Plain text. */
    void showMessage(String text);

    ClientLogger logger();
}
