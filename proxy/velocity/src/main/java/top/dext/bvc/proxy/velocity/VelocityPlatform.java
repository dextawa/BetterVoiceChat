package top.dext.bvc.proxy.velocity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.proxy.platform.BvcLogger;
import top.dext.bvc.proxy.platform.CommandSource;
import top.dext.bvc.proxy.platform.PlatformPlayer;
import top.dext.bvc.proxy.platform.ProxyPlatform;

final class VelocityPlatform implements ProxyPlatform {

    private final Object plugin;
    private final ProxyServer proxy;
    private final Path dataDirectory;
    private final String version;
    private final BvcLogger logger;
    private final ConcurrentHashMap<UUID, VelocityPlayer> players = new ConcurrentHashMap<>();

    VelocityPlatform(Object plugin, ProxyServer proxy, Logger slf4j, Path dataDirectory, String version) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
        this.version = version;
        this.logger = new BvcLogger() {
            @Override
            public void info(String message) {
                slf4j.info(message);
            }

            @Override
            public void warn(String message) {
                slf4j.warn(message);
            }

            @Override
            public void warn(String message, Throwable error) {
                slf4j.warn(message, error);
            }

            @Override
            public void error(String message, Throwable error) {
                slf4j.error(message, error);
            }

            @Override
            public void debug(String message) {
                slf4j.debug(message);
            }
        };
    }

    static Component text(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }

    static ChannelIdentifier channel(String id) {
        return switch (id) {
            case BvcProtocol.CHANNEL_VOICE -> BvcVelocityPlugin.VOICE;
            case BvcProtocol.CHANNEL_SERVER -> BvcVelocityPlugin.SERVER;
            case BvcProtocol.CHANNEL_PROXY -> BvcVelocityPlugin.PROXY;
            default -> com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.from(id);
        };
    }

    /** The one wrapper per online player, so sessions keep a stable object. */
    VelocityPlayer wrap(Player player) {
        return players.computeIfAbsent(player.getUniqueId(), uuid -> new VelocityPlayer(player));
    }

    void forget(Player player) {
        players.remove(player.getUniqueId());
    }

    CommandSource wrapSource(com.velocitypowered.api.command.CommandSource source) {
        if (source instanceof Player player) {
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
                return source.hasPermission(permission);
            }

            @Override
            public void sendMessage(String text) {
                source.sendMessage(text(text));
            }
        };
    }

    @Override
    public String platformName() {
        return "Velocity " + proxy.getVersion().getVersion();
    }

    @Override
    public String pluginVersion() {
        return version;
    }

    @Override
    public Path dataDirectory() {
        return dataDirectory;
    }

    @Override
    public BvcLogger logger() {
        return logger;
    }

    @Override
    public Optional<PlatformPlayer> player(UUID uuid) {
        return proxy.getPlayer(uuid).map(this::wrap);
    }

    @Override
    public Optional<PlatformPlayer> player(String name) {
        return proxy.getPlayer(name).map(this::wrap);
    }

    @Override
    public Collection<? extends PlatformPlayer> players() {
        List<VelocityPlayer> all = new ArrayList<>();
        for (Player player : proxy.getAllPlayers()) {
            all.add(wrap(player));
        }
        return all;
    }

    @Override
    public Collection<String> serverNames() {
        List<String> names = new ArrayList<>();
        for (RegisteredServer server : proxy.getAllServers()) {
            names.add(server.getServerInfo().getName());
        }
        return names;
    }

    @Override
    public CommandSource console() {
        return wrapSource(proxy.getConsoleCommandSource());
    }

    @Override
    public Cancellable scheduleRepeating(Runnable task, long delay, long period, TimeUnit unit) {
        ScheduledTask scheduled = proxy.getScheduler().buildTask(plugin, task).delay(delay, unit).repeat(period, unit).schedule();
        return scheduled::cancel;
    }

    @Override
    public Cancellable scheduleLater(Runnable task, long delay, TimeUnit unit) {
        ScheduledTask scheduled = proxy.getScheduler().buildTask(plugin, task).delay(delay, unit).schedule();
        return scheduled::cancel;
    }
}
