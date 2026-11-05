package top.dext.bvc.backend.folia;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import top.dext.bvc.backend.scheduler.SamplingScheduler;

/**
 * Folia: every player is sampled by a task on their own region thread, so no thread ever reads
 * an entity it does not own.
 */
public final class FoliaSampling implements SamplingScheduler {

    private final Plugin plugin;
    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private volatile Consumer<Player> sampler;
    private volatile int intervalTicks;

    public FoliaSampling(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void start(Consumer<Player> sampler, int intervalTicks) {
        this.sampler = sampler;
        this.intervalTicks = intervalTicks;
        for (Player player : Bukkit.getOnlinePlayers()) {
            track(player);
        }
    }

    @Override
    public void track(Player player) {
        Consumer<Player> current = sampler;
        if (current == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduled -> current.accept(player),
                () -> tasks.remove(uuid), 1L, intervalTicks);
        if (task != null) {
            ScheduledTask previous = tasks.put(uuid, task);
            if (previous != null) {
                previous.cancel();
            }
        }
    }

    @Override
    public void untrack(Player player) {
        ScheduledTask task = tasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    @Override
    public void stop() {
        for (ScheduledTask task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
        sampler = null;
    }
}
