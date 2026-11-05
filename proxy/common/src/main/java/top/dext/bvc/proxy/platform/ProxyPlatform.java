package top.dext.bvc.proxy.platform;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * What BVC needs from Velocity or BungeeCord.
 */
public interface ProxyPlatform {

    /** "Velocity" or "BungeeCord". */
    String platformName();

    String pluginVersion();

    Path dataDirectory();

    BvcLogger logger();

    Optional<PlatformPlayer> player(UUID uuid);

    Optional<PlatformPlayer> player(String name);

    Collection<? extends PlatformPlayer> players();

    Collection<String> serverNames();

    CommandSource console();

    /** Runs a task repeatedly off the network threads. */
    Cancellable scheduleRepeating(Runnable task, long delay, long period, TimeUnit unit);

    /** Runs a task once after a delay, off the network threads. */
    Cancellable scheduleLater(Runnable task, long delay, TimeUnit unit);

    interface Cancellable {
        void cancel();
    }
}
