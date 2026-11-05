package top.dext.bvc.backend.scheduler;

import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Spigot/Paper: one main-thread task samples everyone.
 */
public final class BukkitSampling implements SamplingScheduler {

    private final Plugin plugin;
    private BukkitTask task;

    public BukkitSampling(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void start(final Consumer<Player> sampler, int intervalTicks) {
        task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    sampler.accept(player);
                }
            }
        }, 1L, intervalTicks);
    }

    @Override
    public void track(Player player) {
        // The global task picks the player up.
    }

    @Override
    public void untrack(Player player) {
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
