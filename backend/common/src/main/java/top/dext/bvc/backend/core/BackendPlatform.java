package top.dext.bvc.backend.core;

import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;

/**
 * What {@link BvcBackend} needs from the server software (Bukkit or a mod loader).
 */
public interface BackendPlatform {

    BackendLog log();

    /** Version of this plugin / mod. */
    String pluginVersion();

    /** Server software and version, reported to the proxy. */
    String software();

    /** Where {@code config.yml} lives. */
    Path dataFolder();

    /** UUID of the online player with exactly this name, or {@code null}. */
    UUID playerByName(String name);

    /** Name of an online player, or {@code null}. */
    String nameOf(UUID uuid);

    Collection<String> onlinePlayerNames();

    /** {@code /bvcs reload} changed {@code update-interval-ticks}; reschedule sampling if needed. */
    default void intervalChanged(int ticks) {
    }
}
