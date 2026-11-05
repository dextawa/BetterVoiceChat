package top.dext.bvc.backend.scheduler;

import java.util.function.Consumer;

import org.bukkit.entity.Player;

/**
 * Runs the per-player sampler on the thread that owns each player: the main thread on
 * Bukkit/Paper, the player's region thread on Folia.
 */
public interface SamplingScheduler {

    /** Starts sampling every online (and later joining, via {@link #track}) player. */
    void start(Consumer<Player> sampler, int intervalTicks);

    /** A player joined. */
    void track(Player player);

    /** A player left. */
    void untrack(Player player);

    void stop();
}
