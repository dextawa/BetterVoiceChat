package top.dext.bvc.api;

/**
 * Notified after mute changes. Called on whatever thread made the change; keep it quick.
 */
public interface MuteListener {

    default void onMute(Mute mute) {
    }

    default void onUnmute(Mute mute, String operator) {
    }

    default void onGlobalMuteChanged(boolean enabled, String operator) {
    }

    default void onServerMuteChanged(String server, boolean muted, String operator) {
    }
}
