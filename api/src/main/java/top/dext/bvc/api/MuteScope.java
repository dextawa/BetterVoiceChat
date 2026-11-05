package top.dext.bvc.api;

import java.util.Locale;
import java.util.Objects;

/**
 * Where a mute applies: the whole network, or one sub-server.
 */
public final class MuteScope {

    public static final String GLOBAL_KEY = "*";

    private static final MuteScope GLOBAL = new MuteScope(null);

    private final String server;

    private MuteScope(String server) {
        this.server = server;
    }

    public static MuteScope global() {
        return GLOBAL;
    }

    public static MuteScope server(String server) {
        if (server == null || server.isEmpty() || GLOBAL_KEY.equals(server)) {
            throw new IllegalArgumentException("Use MuteScope.global() for network-wide mutes");
        }
        return new MuteScope(server.toLowerCase(Locale.ROOT));
    }

    /** {@code *} (or empty) means global, anything else is a server name. */
    public static MuteScope parse(String text) {
        return text == null || text.isEmpty() || GLOBAL_KEY.equals(text) ? GLOBAL : server(text);
    }

    public boolean isGlobal() {
        return server == null;
    }

    /** Server name (lower case), or {@code null} for global scope. */
    public String server() {
        return server;
    }

    /** Whether a mute with this scope silences a player who is on {@code currentServer}. */
    public boolean covers(String currentServer) {
        return server == null || server.equalsIgnoreCase(currentServer);
    }

    /** {@code *} for global, otherwise the server name. */
    public String key() {
        return server == null ? GLOBAL_KEY : server;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MuteScope && Objects.equals(server, ((MuteScope) o).server);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(server);
    }

    @Override
    public String toString() {
        return key();
    }
}
