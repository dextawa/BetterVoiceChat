package top.dext.bvc.mod.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BiConsumer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Team;
//? if >=1.19 {
import net.minecraft.network.chat.Component;
//?} else {
/*import net.minecraft.network.chat.TextComponent;
*///?}
//? if >=1.21.11 {
import net.minecraft.server.permissions.Permissions;
//?}
//? if >=1.18 {
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//?} else {
/*import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
*///?}
import top.dext.bvc.backend.core.BackendLog;
import top.dext.bvc.backend.core.BackendPlatform;
import top.dext.bvc.backend.core.BackendPlayer;
import top.dext.bvc.backend.core.BvcBackend;

/**
 * The sub-server side on a modded dedicated server: the same {@link BvcBackend} the Bukkit
 * plugin runs. Loader entry points forward server start/stop, ticks, joins, quits,
 * {@code bvc:proxy} payloads and command registration, and send {@code bvc:server} payloads.
 * Only touches server classes, so it loads on dedicated servers.
 */
public final class BvcModServer implements BackendPlatform {

    //? if >=1.18 {
    private static final Logger LOGGER = LoggerFactory.getLogger("BetterVoiceChat");
    //?} else {
    /*private static final Logger LOGGER = LogManager.getLogger("BetterVoiceChat");
    *///?}

    private static BvcModServer instance;

    private final MinecraftServer server;
    private final Path configDir;
    /** Sends one {@code bvc:server} payload through the loader's networking. */
    private final BiConsumer<ServerPlayer, byte[]> sender;
    private final String version = readModVersion();
    private final BvcBackend backend;
    private int tick;

    private final BackendLog log = new BackendLog() {
        @Override
        public void info(String message) {
            LOGGER.info(message);
        }

        @Override
        public void warn(String message) {
            LOGGER.warn(message);
        }

        @Override
        public void error(String message, Throwable error) {
            LOGGER.error(message, error);
        }
    };

    private BvcModServer(MinecraftServer server, Path configDir, BiConsumer<ServerPlayer, byte[]> sender) {
        this.server = server;
        this.configDir = configDir;
        this.sender = sender;
        this.backend = new BvcBackend(this);
    }

    // ------------------------------------------------------------------ loader events

    /** Server started. Integrated (singleplayer / LAN) servers never sit behind a proxy: ignored. */
    public static void start(MinecraftServer server, Path configDir, BiConsumer<ServerPlayer, byte[]> sender) {
        if (instance != null || !server.isDedicatedServer()) {
            return;
        }
        instance = new BvcModServer(server, configDir, sender);
        instance.backend.start();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            instance.backend.onJoin(instance.wrap(player));
        }
    }

    public static void stop() {
        if (instance != null) {
            instance.backend.stop();
            instance = null;
        }
    }

    /** End of every server tick, on the server thread. */
    public static void onTick() {
        BvcModServer self = instance;
        if (self != null && ++self.tick >= self.backend.intervalTicks()) {
            self.tick = 0;
            for (ServerPlayer player : self.server.getPlayerList().getPlayers()) {
                self.sample(player);
            }
        }
    }

    public static void onJoin(ServerPlayer player) {
        if (instance != null) {
            instance.backend.onJoin(instance.wrap(player));
        }
    }

    public static void onQuit(ServerPlayer player) {
        if (instance != null) {
            instance.backend.onQuit(player.getUUID());
        }
    }

    /** A {@code bvc:proxy} payload from {@code player}'s connection. Any thread. */
    public static void onProxyMessage(ServerPlayer player, byte[] data) {
        if (instance != null) {
            instance.backend.onProxyMessage(instance.wrap(player), data);
        }
    }

    /** {@code /bvcs [args...]} for operators (permission level 2). */
    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bvcs")
                .requires(BvcModServer::isAdmin)
                .executes(context -> run(context, ""))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests((context, builder) -> suggest(builder))
                        .executes(context -> run(context, StringArgumentType.getString(context, "args")))));
    }

    // ------------------------------------------------------------------ internals

    private void sample(ServerPlayer player) {
        String team = null;
        if (backend.wantsTeams()) {
            Team playerTeam = player.getTeam();
            team = playerTeam == null ? null : playerTeam.getName();
        }
        // Mods have no permission plugin: priority and broadcast come from the API only.
        //? if >=1.15 {
        backend.sample(player.getUUID(), dimensionOf(player), player.getX(), player.getY(), player.getZ(),
                player.isSpectator(), team, permission -> false);
        //?} else {
        /*backend.sample(player.getUUID(), dimensionOf(player), player.x, player.y, player.z,
                player.isSpectator(), team, permission -> false);
        *///?}
    }

    /** The dimension id, e.g. {@code minecraft:the_nether}; {@code worlds:} in config.yml uses it. */
    private static String dimensionOf(ServerPlayer player) {
        //? if >=1.21.11 {
        return player.level().dimension().identifier().toString();
        //?} else if >=1.21 {
        /*return player.level().dimension().location().toString();
        *///?} else if >=1.20 {
        /*return player.serverLevel().dimension().location().toString();
        *///?} else if >=1.16 {
        /*return player.getLevel().dimension().location().toString();
        *///?} else {
        /*return net.minecraft.world.level.dimension.DimensionType.getName(player.dimension).toString();
        *///?}
    }

    private static boolean isAdmin(CommandSourceStack source) {
        //? if >=1.21.11 {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        //?} else {
        /*return source.hasPermission(2);
        *///?}
    }

    private static int run(CommandContext<CommandSourceStack> context, String args) {
        BvcModServer self = instance;
        CommandSourceStack source = context.getSource();
        if (self == null) {
            reply(source, "§cBetterVoiceChat 只在独立服务端上运行。");
            return 0;
        }
        String trimmed = args.trim();
        self.backend.command(text -> reply(source, colors(text)), trimmed.isEmpty() ? new String[0] : trimmed.split(" +"));
        return 1;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggest(
            SuggestionsBuilder builder) {
        BvcModServer self = instance;
        if (self != null) {
            String input = builder.getRemaining();
            String[] args = input.split(" ", -1);
            int lastStart = input.lastIndexOf(' ') + 1;
            SuggestionsBuilder last = builder.createOffset(builder.getStart() + lastStart);
            for (String option : self.backend.complete(args)) {
                last.suggest(option);
            }
            return last.buildFuture();
        }
        return builder.buildFuture();
    }

    private static void reply(CommandSourceStack source, String text) {
        //? if >=1.20 {
        source.sendSuccess(() -> Component.literal(text), false);
        //?} else if >=1.19 {
        /*source.sendSuccess(Component.literal(text), false);
        *///?} else {
        /*source.sendSuccess(new TextComponent(text), false);
        *///?}
    }

    /** {@code &a} colour codes to section signs, like Bukkit's translateAlternateColorCodes. */
    static String colors(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(chars[i + 1]) >= 0) {
                chars[i] = '§';
                chars[i + 1] = Character.toLowerCase(chars[i + 1]);
            }
        }
        return new String(chars);
    }

    private BackendPlayer wrap(final ServerPlayer player) {
        return new BackendPlayer() {
            @Override
            public UUID uuid() {
                return player.getUUID();
            }

            @Override
            public String name() {
                return player.getScoreboardName();
            }

            @Override
            public boolean isOnline() {
                return !player.hasDisconnected();
            }

            @Override
            public boolean listensOn(String channel) {
                return true;
            }

            @Override
            public void sendPluginMessage(String channel, byte[] data) {
                sender.accept(player, data);
            }
        };
    }

    private static String readModVersion() {
        Properties properties = new Properties();
        try (InputStream in = BvcModServer.class.getResourceAsStream("/bvc-mod.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException ignored) {
            // fall through
        }
        return properties.getProperty("version", "unknown");
    }

    // ------------------------------------------------------------------ BackendPlatform

    @Override
    public BackendLog log() {
        return log;
    }

    @Override
    public String pluginVersion() {
        return version;
    }

    @Override
    public String software() {
        return server.getServerModName() + " " + server.getServerVersion();
    }

    @Override
    public Path dataFolder() {
        return configDir.resolve("bettervoicechat");
    }

    @Override
    public UUID playerByName(String name) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        return player == null ? null : player.getUUID();
    }

    @Override
    public String nameOf(UUID uuid) {
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        return player == null ? null : player.getScoreboardName();
    }

    @Override
    public Collection<String> onlinePlayerNames() {
        List<String> names = new ArrayList<String>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            names.add(player.getScoreboardName());
        }
        return names;
    }
}
