package top.dext.bvc.proxy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import top.dext.bvc.proxy.platform.BvcLogger;
import top.dext.bvc.proxy.platform.CommandSource;
import top.dext.bvc.proxy.platform.PlatformPlayer;
import top.dext.bvc.proxy.platform.ProxyPlatform;

/**
 * In-memory proxy platform. Delayed tasks are collected and run by the test on demand.
 */
public final class FakePlatform implements ProxyPlatform {

    public final Map<UUID, FakePlayer> players = new LinkedHashMap<>();
    public final List<Runnable> delayed = new ArrayList<>();
    public final List<String> servers = new ArrayList<>(List.of("lobby", "bedwars"));
    public final List<String> consoleOutput = new ArrayList<>();
    private final Path dataDirectory;

    public FakePlatform(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public FakePlayer add(String name) {
        FakePlayer player = new FakePlayer(name);
        players.put(player.uuid, player);
        return player;
    }

    public void runDelayed() {
        List<Runnable> tasks = new ArrayList<>(delayed);
        delayed.clear();
        tasks.forEach(Runnable::run);
    }

    @Override
    public String platformName() {
        return "Fake";
    }

    @Override
    public String pluginVersion() {
        return "test";
    }

    @Override
    public Path dataDirectory() {
        return dataDirectory;
    }

    @Override
    public BvcLogger logger() {
        return new TestLogger();
    }

    @Override
    public Optional<PlatformPlayer> player(UUID uuid) {
        return Optional.ofNullable(players.get(uuid));
    }

    @Override
    public Optional<PlatformPlayer> player(String name) {
        return players.values().stream().filter(p -> p.name.equalsIgnoreCase(name)).map(p -> (PlatformPlayer) p).findFirst();
    }

    @Override
    public Collection<? extends PlatformPlayer> players() {
        return players.values();
    }

    @Override
    public Collection<String> serverNames() {
        return servers;
    }

    @Override
    public CommandSource console() {
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
                return true;
            }

            @Override
            public void sendMessage(String text) {
                consoleOutput.add(text);
            }
        };
    }

    @Override
    public Cancellable scheduleRepeating(Runnable task, long delay, long period, TimeUnit unit) {
        return () -> {
        };
    }

    @Override
    public Cancellable scheduleLater(Runnable task, long delay, TimeUnit unit) {
        delayed.add(task);
        return () -> delayed.remove(task);
    }
}
