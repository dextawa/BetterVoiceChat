package top.dext.bvc.api.proxy;

/**
 * Static access to the proxy API, set when BVC enables.
 */
public final class BvcProxy {

    private static volatile BvcProxyApi instance;

    private BvcProxy() {
    }

    /**
     * @throws IllegalStateException if BVC is not loaded on this proxy
     */
    public static BvcProxyApi get() {
        BvcProxyApi api = instance;
        if (api == null) {
            throw new IllegalStateException("BetterVoiceChat is not enabled on this proxy");
        }
        return api;
    }

    public static boolean isAvailable() {
        return instance != null;
    }

    /** Internal: called by BVC itself. */
    public static void register(BvcProxyApi api) {
        instance = api;
    }
}
