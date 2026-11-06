package top.dext.bvc.mod;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;

//? if >=1.18 {
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//?} else {
/*import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
*///?}
//? if >=1.17 {
import com.mojang.blaze3d.platform.InputConstants;
//?} else {
/*import org.lwjgl.glfw.GLFW;
*///?}

import net.minecraft.SharedConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.Connection;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import top.dext.bvc.client.ClientLogger;
import top.dext.bvc.client.ClientPlatform;
import top.dext.bvc.client.ClientSettings;
import top.dext.bvc.client.VoiceClient;
import top.dext.bvc.client.audio.JavaSoundDevices;

/**
 * Glue between Minecraft and the voice client, shared by the Fabric, Forge and NeoForge entry
 * points. Loader-specific code only registers events and sends payloads.
 */
public final class BvcModClient {

    public static final String MOD_ID = "bettervoicechat";
    //? if >=1.18 {
    private static final Logger LOGGER = LoggerFactory.getLogger("BetterVoiceChat");
    //?} else {
    /*private static final Logger LOGGER = LogManager.getLogger("BetterVoiceChat");
    *///?}

    private static BvcModClient instance;

    private final String loader;
    private final Consumer<BvcPayload> sender;
    private final VoiceClient voice;
    private final VoiceHud hud;

    public KeyMapping talkKey;
    public KeyMapping broadcastKey;
    public KeyMapping muteKey;
    public KeyMapping deafenKey;
    public KeyMapping settingsKey;
    private boolean devSettingsPending = Boolean.getBoolean("bvc.devSettings");
    private int devSettingsTicks;

    private BvcModClient(String loader, Path configDir, Consumer<BvcPayload> sender) {
        this.loader = loader;
        this.sender = sender;
        // Loaders construct mods before Minecraft exists, so the config dir comes from the loader.
        Path config = configDir.resolve("bettervoicechat.properties");
        this.voice = new VoiceClient(new Platform(), ClientSettings.load(config), new JavaSoundDevices());
        this.hud = new VoiceHud(voice);
    }

    /** Translation key of the key mapping category before 1.21.9 (a plain string there). */
    public static final String KEY_CATEGORY = "key.category.bettervoicechat.main";

    /** {@code bettervoicechat:<path>}; the id type was renamed to Identifier in 1.21.11. */
    //? if >=1.21.11 {
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
    //?} else if >=1.21 {
    /*public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
    *///?} else {
    /*public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
    *///?}

    /** Called once by the loader entry point. */
    public static BvcModClient init(String loader, Path configDir, Consumer<BvcPayload> sender) {
        if (instance == null) {
            instance = new BvcModClient(loader, configDir, sender);
        }
        return instance;
    }

    static void log(String message) {
        LOGGER.info(message);
    }

    public static BvcModClient get() {
        return instance;
    }

    public VoiceClient voice() {
        return voice;
    }

    public VoiceHud hud() {
        return hud;
    }

    // Default keys V / B / M / N (InputConstants has no KEY_* constants before 1.17).
    //? if >=1.17 {
    private static final int KEY_V = InputConstants.KEY_V, KEY_B = InputConstants.KEY_B, KEY_M = InputConstants.KEY_M, KEY_N = InputConstants.KEY_N, KEY_K = InputConstants.KEY_K;
    //?} else {
    /*private static final int KEY_V = GLFW.GLFW_KEY_V, KEY_B = GLFW.GLFW_KEY_B, KEY_M = GLFW.GLFW_KEY_M, KEY_N = GLFW.GLFW_KEY_N, KEY_K = GLFW.GLFW_KEY_K;
    *///?}

    /** Creates the key mappings; the entry point registers them with its loader. */
    //? if >=1.21.9 {
    public KeyMapping[] createKeys(KeyMapping.Category category) {
    //?} else {
    /*public KeyMapping[] createKeys(String category) {
    *///?}
        talkKey = new KeyMapping("key.bettervoicechat.talk", KEY_V, category);
        broadcastKey = new KeyMapping("key.bettervoicechat.broadcast", KEY_B, category);
        muteKey = new KeyMapping("key.bettervoicechat.mute", KEY_M, category);
        deafenKey = new KeyMapping("key.bettervoicechat.deafen", KEY_N, category);
        settingsKey = new KeyMapping("key.bettervoicechat.settings", KEY_K, category);
        return new KeyMapping[] {talkKey, broadcastKey, muteKey, deafenKey, settingsKey};
    }

    // ------------------------------------------------------------------ events from the loader

    /** The play state is ready (first join, or after a proxy server switch). */
    public void onJoin(Connection connection) {
        SocketAddress remote = connection.getRemoteAddress();
        if (!(remote instanceof InetSocketAddress)) {
            return; // singleplayer / LAN integrated server: no proxy, no voice
        }
        InetSocketAddress address = (InetSocketAddress) remote;
        ServerData server = Minecraft.getInstance().getCurrentServer();
        voice.onJoinServer(address, server == null ? "" : hostOf(server.ip));
    }

    public void onDisconnect() {
        voice.onDisconnect();
    }

    public void onPayload(BvcPayload payload) {
        voice.onPluginMessage(payload.data());
    }

    /** Once per client tick, on the game thread. */
    public void onTick(Minecraft mc) {
        //? if <1.17 {
        /*devJoin(mc);
        *///?}
        if (talkKey == null) {
            return;
        }
        voice.setTalkKey(talkKey.isDown());
        voice.setBroadcastKey(broadcastKey.isDown());
        while (muteKey.consumeClick()) {
            voice.toggleMicrophoneMuted();
        }
        while (deafenKey.consumeClick()) {
            voice.toggleDeafened();
        }
        // Dev runs: -Dbvc.devSettings opens the screen once voice is connected (screenshot tests).
        boolean devOpen = devSettingsPending && voice.status().isConnected() && ++devSettingsTicks > 40;
        if (devOpen) {
            devSettingsPending = false;
        }
        while (devOpen || settingsKey.consumeClick()) {
            devOpen = false;
            //? if >=26.2 {
            boolean noScreen = mc.gui.screen() == null;
            //?} else {
            /*boolean noScreen = mc.screen == null;
            *///?}
            if (noScreen) {
                //? if >=26.2 {
                mc.setScreenAndShow(new VoiceSettingsScreen(null, voice));
                //?} else {
                /*mc.setScreen(new VoiceSettingsScreen(null, voice));
                *///?}
            }
        }

        if (mc.player == null || mc.level == null) {
            voice.positions().clear();
            return;
        }
        Map<UUID, double[]> others = new HashMap<>();
        for (AbstractClientPlayer player : mc.level.players()) {
            if (player != mc.player) {
                others.put(player.getUUID(), new double[] {player.getX(), player.getEyeY(), player.getZ()});
            }
        }
        //? if >=1.17 {
        float yaw = mc.player.getYRot();
        //?} else {
        /*float yaw = mc.player.yRot;
        *///?}
        voice.positions().update(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(), yaw, others);
    }

    //? if <1.17 {
    /*private boolean devJoined;

    // 1.16.5 drops the --server connect screen behind the loading overlay. Dev runs pass
    // -Dbvc.devJoin=host:port instead; never set in normal play.
    private void devJoin(Minecraft mc) {
        String target = System.getProperty("bvc.devJoin");
        if (devJoined || target == null || !(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)) {
            return;
        }
        devJoined = true;
        int colon = target.lastIndexOf(':');
        mc.setScreen(new net.minecraft.client.gui.screens.ConnectScreen(mc.screen, mc,
                target.substring(0, colon), Integer.parseInt(target.substring(colon + 1))));
    }
    *///?}

    public void onShutdown() {
        voice.settings().save();
        voice.shutdown();
    }

    /** "play.example.com:25565" -> "play.example.com" (keeps IPv6 literals intact). */
    static String hostOf(String ip) {
        if (ip == null) {
            return "";
        }
        String value = ip.trim();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 0 ? value.substring(1, end) : value;
        }
        int colon = value.lastIndexOf(':');
        return colon > 0 && value.indexOf(':') == colon ? value.substring(0, colon) : value;
    }

    private static String readModVersion() {
        Properties properties = new Properties();
        try (InputStream in = BvcModClient.class.getResourceAsStream("/bvc-mod.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException ignored) {
            // fall through
        }
        return properties.getProperty("version", "unknown");
    }

    private final class Platform implements ClientPlatform {
        private final String version = readModVersion();
        private final ClientLogger logger = new ClientLogger() {
            @Override
            public void info(String message) {
                LOGGER.info(message);
            }

            @Override
            public void warn(String message) {
                LOGGER.warn(message);
            }

            @Override
            public void warn(String message, Throwable error) {
                LOGGER.warn(message, error);
            }

            @Override
            public void debug(String message) {
                LOGGER.debug(message);
            }
        };

        @Override
        public void sendPluginMessage(byte[] payload) {
            Minecraft mc = Minecraft.getInstance();
            // Payloads must leave on the game thread; the capture thread sends B-transport audio.
            mc.execute(() -> {
                if (mc.getConnection() != null) {
                    sender.accept(new BvcPayload(payload));
                }
            });
        }

        @Override
        public String minecraftVersion() {
            //? if >=1.21.6 {
            return SharedConstants.getCurrentVersion().name();
            //?} else {
            /*return SharedConstants.getCurrentVersion().getName();
            *///?}
        }

        @Override
        public String loader() {
            return loader;
        }

        @Override
        public String modVersion() {
            return version;
        }

        @Override
        public int minecraftProtocol() {
            return SharedConstants.getProtocolVersion();
        }

        @Override
        public void showMessage(String text) {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.player != null) {
                    //? if >=26 {
                    mc.player.sendSystemMessage(VoiceHud.literal("[BVC] " + text));
                    //?} else {
                    /*mc.player.displayClientMessage(VoiceHud.literal("[BVC] " + text), false);
                    *///?}
                }
            });
        }

        @Override
        public ClientLogger logger() {
            return logger;
        }
    }
}
