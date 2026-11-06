package top.dext.bvc.backend.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import top.dext.bvc.api.server.BvcServer;

/**
 * The whole sub-server side behind one object. Platforms (the Bukkit plugin, the server mods)
 * register {@code bvc:proxy} in and {@code bvc:server} out, forward joins, quits, messages and
 * {@code /bvcs}, and call {@link #sample} every {@link #intervalTicks()} ticks on the thread that
 * owns each player.
 */
public final class BvcBackend {

    private static final String CONFIG = "config.yml";

    private final BackendPlatform platform;
    private final ProxyLink link;
    private final VoiceEngine engine;
    private final BvcsCommand command;

    public BvcBackend(BackendPlatform platform) {
        this.platform = platform;
        BackendSettings settings = loadSettings();
        this.link = new ProxyLink(platform.log(), platform.pluginVersion(), platform.software());
        this.engine = new VoiceEngine(link, platform.log(), settings);
        this.command = new BvcsCommand(this);
    }

    public void start() {
        engine.start();
        BvcServer.register(new BackendVoiceApi(engine, link));
        platform.log().info("Voice zone: " + BvcsCommand.describe(engine.rules().defaultZone));
    }

    public void stop() {
        BvcServer.register(null);
        engine.stop();
    }

    /** Re-reads {@code config.yml}. */
    public void reload() {
        BackendSettings settings = loadSettings();
        int oldInterval = engine.settings().intervalTicks;
        engine.apply(settings);
        if (settings.intervalTicks != oldInterval) {
            engine.stop();
            engine.start();
            platform.intervalChanged(settings.intervalTicks);
        }
    }

    /** Ticks between two samples of the same player. */
    public int intervalTicks() {
        return engine.settings().intervalTicks;
    }

    // ------------------------------------------------------------------ platform events

    public void onJoin(BackendPlayer player) {
        link.onJoin(player);
    }

    public void onQuit(UUID uuid) {
        link.onQuit(uuid);
        engine.forget(uuid);
    }

    /** A {@code bvc:proxy} plugin message arrived on {@code player}'s connection. Any thread. */
    public void onProxyMessage(BackendPlayer player, byte[] data) {
        link.onProxyMessage(player, data);
    }

    /** Whether {@link #sample} uses the scoreboard team; skip reading it otherwise. */
    public boolean wantsTeams() {
        return engine.wantsTeams();
    }

    /**
     * Called on the thread that owns the player.
     *
     * @param team        scoreboard team name, or {@code null}
     * @param permissions checks a permission node; only called every few seconds
     */
    public void sample(UUID uuid, String world, double x, double y, double z, boolean spectator, String team,
                       Predicate<String> permissions) {
        engine.sample(uuid, world, x, y, z, spectator, team, permissions);
    }

    /** {@code /bvcs <args>}; the caller already checked {@code bvc.admin}. */
    public void command(BackendSender sender, String[] args) {
        command.execute(sender, args);
    }

    public List<String> complete(String[] args) {
        return command.complete(args);
    }

    // ------------------------------------------------------------------ internals

    BackendPlatform platform() {
        return platform;
    }

    ProxyLink link() {
        return link;
    }

    VoiceEngine engine() {
        return engine;
    }

    private BackendSettings loadSettings() {
        Map<String, Object> config;
        try {
            config = MiniYaml.parse(readConfig());
        } catch (IOException | IllegalArgumentException e) {
            platform.log().error("Could not read " + CONFIG + ", using the defaults", e);
            config = Collections.emptyMap();
        }
        return BackendSettings.read(config, platform.log());
    }

    /** Reads {@code config.yml}, writing the bundled default first if it is missing. */
    private String readConfig() throws IOException {
        Path file = platform.dataFolder().resolve(CONFIG);
        if (Files.notExists(file)) {
            Files.createDirectories(file.getParent());
            try (InputStream in = BvcBackend.class.getResourceAsStream(CONFIG)) {
                if (in == null) {
                    throw new IOException("Missing bundled " + CONFIG);
                }
                Files.copy(in, file);
            }
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
