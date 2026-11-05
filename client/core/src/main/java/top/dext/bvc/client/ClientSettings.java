package top.dext.bvc.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The player's local preferences, stored in {@code config/bettervoicechat.properties}.
 * Thread-safe; call {@link #save()} after changes.
 */
public final class ClientSettings {

    private final Path file;
    private volatile ActivationMode activation = ActivationMode.PUSH_TO_TALK;
    private volatile float voiceThresholdDb = -45f;
    private volatile String inputDevice = "";
    private volatile String outputDevice = "";
    private volatile float inputGain = 1f;
    private volatile float outputVolume = 1f;
    private volatile boolean hudEnabled = true;
    private final Map<UUID, Float> playerVolumes = new ConcurrentHashMap<UUID, Float>();
    private final Map<UUID, Boolean> playerMuted = new ConcurrentHashMap<UUID, Boolean>();

    private ClientSettings(Path file) {
        this.file = file;
    }

    /** Settings that are never saved (tests, or no config folder). */
    public static ClientSettings inMemory() {
        return new ClientSettings(null);
    }

    public static ClientSettings load(Path file) {
        ClientSettings settings = new ClientSettings(file);
        if (file == null || !Files.exists(file)) {
            return settings;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            return settings;
        }
        settings.activation = parseEnum(properties.getProperty("activation"), ActivationMode.PUSH_TO_TALK);
        settings.voiceThresholdDb = parseFloat(properties.getProperty("voice.threshold.db"), -45f, -90f, 0f);
        settings.inputDevice = properties.getProperty("input.device", "");
        settings.outputDevice = properties.getProperty("output.device", "");
        settings.inputGain = parseFloat(properties.getProperty("input.gain"), 1f, 0f, 4f);
        settings.outputVolume = parseFloat(properties.getProperty("output.volume"), 1f, 0f, 2f);
        settings.hudEnabled = !"false".equals(properties.getProperty("hud.enabled"));
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("player.")) {
                String[] parts = key.split("\\.");
                if (parts.length != 3) {
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(parts[1]);
                    if (parts[2].equals("volume")) {
                        settings.playerVolumes.put(uuid, parseFloat(properties.getProperty(key), 1f, 0f, 2f));
                    } else if (parts[2].equals("muted") && "true".equals(properties.getProperty(key))) {
                        settings.playerMuted.put(uuid, Boolean.TRUE);
                    }
                } catch (IllegalArgumentException ignored) {
                    // skip junk keys
                }
            }
        }
        return settings;
    }

    public synchronized void save() {
        if (file == null) {
            return;
        }
        Properties properties = new Properties();
        properties.setProperty("activation", activation.name());
        properties.setProperty("voice.threshold.db", Float.toString(voiceThresholdDb));
        properties.setProperty("input.device", inputDevice);
        properties.setProperty("output.device", outputDevice);
        properties.setProperty("input.gain", Float.toString(inputGain));
        properties.setProperty("output.volume", Float.toString(outputVolume));
        properties.setProperty("hud.enabled", Boolean.toString(hudEnabled));
        for (Map.Entry<UUID, Float> entry : playerVolumes.entrySet()) {
            properties.setProperty("player." + entry.getKey() + ".volume", Float.toString(entry.getValue()));
        }
        for (UUID uuid : playerMuted.keySet()) {
            properties.setProperty("player." + uuid + ".muted", "true");
        }
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "BetterVoiceChat client settings");
            }
        } catch (IOException ignored) {
            // settings are a convenience; never crash the game over them
        }
    }

    public ActivationMode activation() {
        return activation;
    }

    public void setActivation(ActivationMode activation) {
        this.activation = activation;
    }

    public float voiceThresholdDb() {
        return voiceThresholdDb;
    }

    public void setVoiceThresholdDb(float db) {
        this.voiceThresholdDb = Math.max(-90f, Math.min(0f, db));
    }

    public String inputDevice() {
        return inputDevice;
    }

    public void setInputDevice(String name) {
        this.inputDevice = name == null ? "" : name;
    }

    public String outputDevice() {
        return outputDevice;
    }

    public void setOutputDevice(String name) {
        this.outputDevice = name == null ? "" : name;
    }

    public float inputGain() {
        return inputGain;
    }

    public void setInputGain(float gain) {
        this.inputGain = Math.max(0f, Math.min(4f, gain));
    }

    public float outputVolume() {
        return outputVolume;
    }

    public void setOutputVolume(float volume) {
        this.outputVolume = Math.max(0f, Math.min(2f, volume));
    }

    public boolean hudEnabled() {
        return hudEnabled;
    }

    public void setHudEnabled(boolean enabled) {
        this.hudEnabled = enabled;
    }

    /** Local volume for one player, 0..2 (1 = normal). Muted players return 0. */
    public float playerVolume(UUID player) {
        if (player == null) {
            return 1f;
        }
        if (playerMuted.containsKey(player)) {
            return 0f;
        }
        Float volume = playerVolumes.get(player);
        return volume == null ? 1f : volume;
    }

    public void setPlayerVolume(UUID player, float volume) {
        float clamped = Math.max(0f, Math.min(2f, volume));
        if (clamped == 1f) {
            playerVolumes.remove(player);
        } else {
            playerVolumes.put(player, clamped);
        }
    }

    public boolean isPlayerMuted(UUID player) {
        return playerMuted.containsKey(player);
    }

    public void setPlayerMuted(UUID player, boolean muted) {
        if (muted) {
            playerMuted.put(player, Boolean.TRUE);
        } else {
            playerMuted.remove(player);
        }
    }

    public Map<UUID, Boolean> mutedPlayers() {
        return Collections.unmodifiableMap(playerMuted);
    }

    private static float parseFloat(String text, float fallback, float min, float max) {
        if (text == null) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Float.parseFloat(text.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static ActivationMode parseEnum(String text, ActivationMode fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            return ActivationMode.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
