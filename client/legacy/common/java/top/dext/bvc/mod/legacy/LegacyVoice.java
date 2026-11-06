package top.dext.bvc.mod.legacy;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import top.dext.bvc.client.ActivationMode;
import top.dext.bvc.client.ClientLogger;
import top.dext.bvc.client.ClientPlatform;
import top.dext.bvc.client.ClientSettings;
import top.dext.bvc.client.ClientStatus;
import top.dext.bvc.client.VoiceClient;
import top.dext.bvc.client.audio.JavaSoundDevices;
import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.data.AudioCategory;
import top.dext.bvc.protocol.packet.ProxyToClient;

/**
 * Minecraft-free half of the 1.8.9 / 1.12.2 mods: owns the voice client and builds the HUD text.
 * Each version only supplies a {@link Game} (sending, scheduling, translation, chat) and draws.
 * Shows only what the server decided; players cannot change mode or team here.
 */
public final class LegacyVoice {

    public static final String MOD_ID = "bettervoicechat";
    public static final String CHANNEL = BvcProtocol.CHANNEL_VOICE;
    public static final String KEY_CATEGORY = "key.category.bettervoicechat.main";

    public static final int WHITE = 0xFFFFFFFF;
    public static final int GRAY = 0xFFAAAAAA;
    public static final int GREEN = 0xFF55FF55;
    public static final int RED = 0xFFFF5555;
    public static final int YELLOW = 0xFFFFFF55;
    public static final int GOLD = 0xFFFFAA00;
    public static final int BACKGROUND = 0x80000000;
    private static final int MAX_SPEAKERS = 8;

    private static final Logger LOGGER = LogManager.getLogger("BetterVoiceChat");

    /** What the version-specific code provides. */
    public interface Game {
        /** Sends one {@code bvc:voice} plugin message; called on the game thread. */
        void sendPayload(byte[] data);

        /** True while connected to a server (the payload can be sent). */
        boolean connected();

        void runOnGameThread(Runnable task);

        String translate(String key, Object... args);

        void chat(String text);

        String minecraftVersion();

        int minecraftProtocol();
    }

    /** One HUD line. */
    public static final class Line {
        public final String text;
        public final int color;

        Line(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    private final Game game;
    private final VoiceClient voice;
    private Object connection;

    public LegacyVoice(Game game, File configDir) {
        this.game = game;
        this.voice = new VoiceClient(new Platform(), ClientSettings.load(new File(configDir, "bettervoicechat.properties").toPath()),
                new JavaSoundDevices());
    }

    public VoiceClient voice() {
        return voice;
    }

    // ------------------------------------------------------------------ events

    /**
     * Called every client tick with the current network connection object (or null). A new
     * object means a new server connection; null means disconnected.
     */
    public void updateConnection(Object current, SocketAddress remote, String serverIp) {
        if (current == connection) {
            return;
        }
        connection = current;
        if (current == null) {
            voice.onDisconnect();
            return;
        }
        if (!(remote instanceof InetSocketAddress)) {
            voice.onDisconnect(); // singleplayer / LAN integrated server: no proxy, no voice
            return;
        }
        voice.onJoinServer((InetSocketAddress) remote, hostOf(serverIp));
    }

    public void onPayload(byte[] data) {
        voice.onPluginMessage(data);
    }

    public void onKeys(boolean talk, boolean broadcast, int mutePresses, int deafenPresses) {
        voice.setTalkKey(talk);
        voice.setBroadcastKey(broadcast);
        for (int i = 0; i < mutePresses; i++) {
            voice.toggleMicrophoneMuted();
        }
        for (int i = 0; i < deafenPresses; i++) {
            voice.toggleDeafened();
        }
    }

    public void updatePositions(double x, double y, double z, float yaw, Map<UUID, double[]> others) {
        voice.positions().update(x, y, z, yaw, others);
    }

    public void clearPositions() {
        voice.positions().clear();
    }

    public void shutdown() {
        voice.settings().save();
        voice.shutdown();
    }

    // ------------------------------------------------------------------ HUD

    /** Own state and zone, drawn bottom left; empty when there is nothing to show. */
    public List<Line> statusLines() {
        if (!voice.settings().hudEnabled()) {
            return Collections.emptyList();
        }
        ClientStatus status = voice.status();
        if (status.phase == ClientStatus.Phase.IDLE || status.phase == ClientStatus.Phase.UNAVAILABLE) {
            return Collections.emptyList();
        }
        List<Line> lines = new ArrayList<Line>();
        lines.add(stateLine(status));
        if (status.isConnected() && status.voice != null) {
            lines.add(new Line(zoneText(status), GRAY));
        }
        return lines;
    }

    /** Who is talking, drawn top left. */
    public List<Line> speakerLines() {
        if (!voice.settings().hudEnabled()) {
            return Collections.emptyList();
        }
        List<Line> lines = new ArrayList<Line>();
        for (ClientStatus.Speaking speaking : voice.status().speaking) {
            if (lines.size() == MAX_SPEAKERS) {
                break;
            }
            int color = speaking.category == AudioCategory.BROADCAST ? GOLD
                    : speaking.category == AudioCategory.GROUP ? GREEN : WHITE;
            lines.add(new Line("▶ " + speaking.name, color));
        }
        return lines;
    }

    private Line stateLine(ClientStatus status) {
        switch (status.phase) {
            case CONNECTING:
                return line("hud.bettervoicechat.connecting", GRAY);
            case RECONNECTING:
                return line("hud.bettervoicechat.reconnecting", YELLOW);
            case INCOMPATIBLE:
                return line("hud.bettervoicechat.incompatible", RED);
            default:
                break;
        }
        ProxyToClient.MuteStatus mute = status.mute;
        if (mute != null && mute.muted) {
            if (mute.expiresAt > 0) {
                long minutes = Math.max(1, (mute.expiresAt - System.currentTimeMillis() + 59_999) / 60_000);
                return new Line(game.translate("hud.bettervoicechat.muted_for", minutes), RED);
            }
            return line("hud.bettervoicechat.muted", RED);
        }
        if (!status.inputAvailable) {
            return line("hud.bettervoicechat.no_microphone", RED);
        }
        if (status.deafened) {
            return line("hud.bettervoicechat.deafened", RED);
        }
        if (status.microphoneMuted) {
            return line("hud.bettervoicechat.mic_off", GRAY);
        }
        if (status.voice != null && !status.voice.canSpeak) {
            return line("hud.bettervoicechat.cannot_speak", GRAY);
        }
        if (status.talking) {
            return line("hud.bettervoicechat.talking", GREEN);
        }
        return line(status.activation == ActivationMode.VOICE
                ? "hud.bettervoicechat.listening_voice" : "hud.bettervoicechat.ready", WHITE);
    }

    private Line line(String key, int color) {
        return new Line(game.translate(key), color);
    }

    private String zoneText(ClientStatus status) {
        ProxyToClient.VoiceState state = status.voice;
        String mode;
        switch (state.mode) {
            case PROXIMITY:
                mode = game.translate("hud.bettervoicechat.mode.proximity", (int) state.radius);
                break;
            case TEAM:
                mode = game.translate("hud.bettervoicechat.mode.team");
                break;
            case FREE:
                mode = game.translate("hud.bettervoicechat.mode.free");
                break;
            case TEAM_PROXIMITY:
                mode = game.translate("hud.bettervoicechat.mode.team_proximity", (int) state.radius);
                break;
            default:
                mode = game.translate("hud.bettervoicechat.mode.disabled");
                break;
        }
        return mode + " · " + game.translate("hud.bettervoicechat.activation." + status.activation.name().toLowerCase());
    }

    // ------------------------------------------------------------------ helpers

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
        try (InputStream in = LegacyVoice.class.getResourceAsStream("/bvc-mod.properties")) {
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
        public void sendPluginMessage(final byte[] payload) {
            // Payloads must leave on the game thread; the capture thread sends B-transport audio.
            game.runOnGameThread(new Runnable() {
                @Override
                public void run() {
                    if (game.connected()) {
                        game.sendPayload(payload);
                    }
                }
            });
        }

        @Override
        public String minecraftVersion() {
            return game.minecraftVersion();
        }

        @Override
        public String loader() {
            return "forge";
        }

        @Override
        public String modVersion() {
            return version;
        }

        @Override
        public int minecraftProtocol() {
            return game.minecraftProtocol();
        }

        @Override
        public void showMessage(final String text) {
            game.runOnGameThread(new Runnable() {
                @Override
                public void run() {
                    game.chat("[BVC] " + text);
                }
            });
        }

        @Override
        public ClientLogger logger() {
            return logger;
        }
    }
}
