package top.dext.bvc.api.server;

/**
 * Static access to the sub-server API, set when the BVC Bukkit plugin enables.
 */
public final class BvcServer {

    private static volatile BvcServerApi instance;

    private BvcServer() {
    }

    /**
     * @throws IllegalStateException if the BVC plugin is not enabled on this server
     */
    public static BvcServerApi get() {
        BvcServerApi api = instance;
        if (api == null) {
            throw new IllegalStateException("BetterVoiceChat is not enabled on this server");
        }
        return api;
    }

    public static boolean isAvailable() {
        return instance != null;
    }

    /** Internal: called by BVC itself. */
    public static void register(BvcServerApi api) {
        instance = api;
    }
}
