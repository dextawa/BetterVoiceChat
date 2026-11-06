package top.dext.bvc.backend;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scoreboard.Team;

import top.dext.bvc.backend.core.BackendLog;
import top.dext.bvc.backend.core.BackendPlatform;
import top.dext.bvc.backend.core.BackendPlayer;
import top.dext.bvc.backend.core.BvcBackend;
import top.dext.bvc.backend.scheduler.BukkitSampling;
import top.dext.bvc.backend.scheduler.SamplingScheduler;
import top.dext.bvc.protocol.BvcProtocol;

/**
 * Bukkit / Paper / Folia glue around {@link BvcBackend}.
 */
public final class BvcBukkitPlugin extends JavaPlugin implements Listener, BackendPlatform, PluginMessageListener,
        CommandExecutor, TabCompleter {

    private static final String FOLIA_SAMPLING = "top.dext.bvc.backend.folia.FoliaSampling";

    private BvcBackend backend;
    private SamplingScheduler sampling;
    private BackendLog log;
    private volatile boolean scoreboardWarned;

    @Override
    public void onEnable() {
        log = new BackendLog() {
            @Override
            public void info(String message) {
                getLogger().info(message);
            }

            @Override
            public void warn(String message) {
                getLogger().warning(message);
            }

            @Override
            public void error(String message, Throwable error) {
                getLogger().log(Level.SEVERE, message, error);
            }
        };
        backend = new BvcBackend(this);

        Messenger messenger = getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(this, BvcProtocol.CHANNEL_SERVER);
        messenger.registerIncomingPluginChannel(this, BvcProtocol.CHANNEL_PROXY, this);

        backend.start();
        startSampling();

        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            backend.onJoin(new BukkitPlayer(player));
        }

        PluginCommand bvcs = getCommand("bvcs");
        if (bvcs != null) {
            bvcs.setExecutor(this);
            bvcs.setTabCompleter(this);
        }
        if (isFolia()) {
            getLogger().info("Running on Folia");
        }
    }

    @Override
    public void onDisable() {
        if (sampling != null) {
            sampling.stop();
        }
        if (backend != null) {
            backend.stop();
        }
        Messenger messenger = getServer().getMessenger();
        messenger.unregisterOutgoingPluginChannel(this);
        messenger.unregisterIncomingPluginChannel(this);
    }

    private void startSampling() {
        sampling = createSampling();
        sampling.start(this::sample, backend.intervalTicks());
        for (Player player : Bukkit.getOnlinePlayers()) {
            sampling.track(player);
        }
    }

    /** Runs on the thread that owns {@code player}. */
    private void sample(final Player player) {
        Location location = player.getLocation();
        if (location.getWorld() == null) {
            return;
        }
        String team = backend.wantsTeams() ? teamOf(player) : null;
        backend.sample(player.getUniqueId(), location.getWorld().getName(), location.getX(), location.getY(),
                location.getZ(), player.getGameMode() == GameMode.SPECTATOR, team, player::hasPermission);
    }

    private String teamOf(Player player) {
        try {
            // The player's own scoreboard first: minigames usually give each player one.
            Team team = player.getScoreboard().getEntryTeam(player.getName());
            if (team == null) {
                team = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(player.getName());
            }
            return team == null ? null : team.getName();
        } catch (RuntimeException e) {
            // Folia restricts scoreboard access off the global thread.
            if (!scoreboardWarned) {
                scoreboardWarned = true;
                getLogger().warning("Scoreboard teams are not readable here (" + e + "); set groups through the API instead");
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        backend.onJoin(new BukkitPlayer(event.getPlayer()));
        sampling.track(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        sampling.untrack(player);
        backend.onQuit(player.getUniqueId());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (BvcProtocol.CHANNEL_PROXY.equals(channel)) {
            backend.onProxyMessage(new BukkitPlayer(player), message);
        }
    }

    @Override
    public boolean onCommand(final CommandSender sender, Command command, String label, String[] args) {
        backend.command(text -> sender.sendMessage(ChatColor.translateAlternateColorCodes('&', text)), args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return backend.complete(args);
    }

    // ------------------------------------------------------------------ BackendPlatform

    @Override
    public BackendLog log() {
        return log;
    }

    @Override
    public String pluginVersion() {
        return getDescription().getVersion();
    }

    @Override
    public String software() {
        return Bukkit.getName() + " " + Bukkit.getVersion();
    }

    @Override
    public Path dataFolder() {
        return getDataFolder().toPath();
    }

    @Override
    public UUID playerByName(String name) {
        Player player = Bukkit.getPlayerExact(name);
        return player == null ? null : player.getUniqueId();
    }

    @Override
    public String nameOf(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player == null ? null : player.getName();
    }

    @Override
    public Collection<String> onlinePlayerNames() {
        List<String> names = new ArrayList<String>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    @Override
    public void intervalChanged(int ticks) {
        sampling.stop();
        startSampling();
    }

    private final class BukkitPlayer implements BackendPlayer {

        private final Player player;

        BukkitPlayer(Player player) {
            this.player = player;
        }

        @Override
        public UUID uuid() {
            return player.getUniqueId();
        }

        @Override
        public String name() {
            return player.getName();
        }

        @Override
        public boolean isOnline() {
            return player.isOnline();
        }

        @Override
        public boolean listensOn(String channel) {
            return player.getListeningPluginChannels().contains(channel);
        }

        @Override
        public void sendPluginMessage(String channel, byte[] data) {
            player.sendPluginMessage(BvcBukkitPlugin.this, channel, data);
        }
    }

    private SamplingScheduler createSampling() {
        if (isFolia()) {
            try {
                return (SamplingScheduler) Class.forName(FOLIA_SAMPLING).getConstructor(org.bukkit.plugin.Plugin.class)
                        .newInstance(this);
            } catch (ReflectiveOperationException | LinkageError e) {
                getLogger().log(Level.SEVERE, "Folia detected but its scheduler could not be loaded", e);
            }
        }
        return new BukkitSampling(this);
    }

    static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
