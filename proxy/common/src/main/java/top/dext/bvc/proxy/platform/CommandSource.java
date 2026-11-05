package top.dext.bvc.proxy.platform;

import java.util.UUID;

/**
 * Someone who can run BVC commands: a player or the console.
 */
public interface CommandSource {

    String name();

    /** {@code null} for the console. */
    UUID uuid();

    boolean hasPermission(String permission);

    /** Text with {@code &} colour codes. */
    void sendMessage(String text);
}
