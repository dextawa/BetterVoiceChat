package top.dext.bvc.proxy.bungee;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import top.dext.bvc.proxy.platform.BvcLogger;
import top.dext.bvc.proxy.platform.CommandSource;
import top.dext.bvc.proxy.platform.PlatformPlayer;
import top.dext.bvc.proxy.platform.ProxyPlatform;

final class BungeePlatform implements ProxyPlatform {

    private final BvcBungeePlugin plugin;
    private final BvcLogger logger;
    private final ConcurrentHashMap<UUID, BungeePlayer> players = new ConcurrentHashMap<>();

    BungeePlatform(BvcBungeePlugin plugin) {
        this.plugin = plugin;
        Logger jul = plugin.getLogger();
        this.logger = new BvcLogger() {
            @Override
            public void info(String message) {
                jul.info(message);
            }

            @Override
            public void warn(String message) {
                jul.warning(message);
            }

            @Override
            public void warn(String message, Throwable error) {
                jul.log(Level.WARNING, message, error);
            }

            @Override
            public void error(String message, Throwable error) {
                jul.log(Level.SEVERE, message, error);
            }

            @Override
            public void debug(String message) {
                jul.fine(message);
            }
        };
    }

    static BaseComponent text(String legacy) {
        return TextComponent.fromLegacy(ChatColor.translateAlternateColorCodes('&', legacy));
    }

    /** The one wrapper per online player, so sessions keep a stable object. */
    BungeePlayer wrap(ProxiedPlayer player) {
        return players.computeIfAbsent(player.getUniqueId(), uuid -> new BungeePlayer(player));
    }

    void forget(ProxiedPlayer player) {
        players.remove(player.getUniqueId());
    }

    CommandSource wrapSource(CommandSender sender) {
        if (sender instanceof ProxiedPlayer player) {
            return wrap(player);
        }
        return new CommandSource() {
            @Override
            public String name() {
                return "Console";
            }

            @Override
            public UUID uuid() {
                return null;
            }

            @Override
            public boolean hasPermission(String permission) {
                return sender.hasPermission(permission);
            }

            @Override
            public void sendMessage(String text) {
                sender.sendMessage(text(text));
            }
        };
    }

    @Override
    public String platformName() {
        return plugin.getProxy().getName() + " " + plugin.getProxy().getVersion();
    }

    @Override
    public String pluginVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public Path dataDirectory() {
        return plugin.getDataFolder().toPath();
    }

    @Override
    public BvcLogger logger() {
        return logger;
    }

    @Override
    public Optional<PlatformPlayer> player(UUID uuid) {
        ProxiedPlayer player = plugin.getProxy().getPlayer(uuid);
        return player == null ? Optional.empty() : Optional.of(wrap(player));
    }

    @Override
    public Optional<PlatformPlayer> player(String name) {
        ProxiedPlayer player = plugin.getProxy().getPlayer(name);
        return player == null ? Optional.empty() : Optional.of(wrap(player));
    }

    @Override
    public Collection<? extends PlatformPlayer> players() {
        List<BungeePlayer> all = new ArrayList<>();
        for (ProxiedPlayer player : plugin.getProxy().getPlayers()) {
            all.add(wrap(player));
        }
        return all;
    }

    @Override
    public Collection<String> serverNames() {
        return new ArrayList<>(plugin.getProxy().getServers().keySet());
    }

    @Override
    public CommandSource console() {
        return wrapSource(plugin.getProxy().getConsole());
    }

    @Override
    public Cancellable scheduleRepeating(Runnable task, long delay, long period, TimeUnit unit) {
        ScheduledTask scheduled = plugin.getProxy().getScheduler().schedule(plugin, task, delay, period, unit);
        return scheduled::cancel;
    }

    @Override
    public Cancellable scheduleLater(Runnable task, long delay, TimeUnit unit) {
        ScheduledTask scheduled = plugin.getProxy().getScheduler().schedule(plugin, task, delay, unit);
        return scheduled::cancel;
    }
}
