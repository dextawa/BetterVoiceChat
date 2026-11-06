package top.dext.bvc.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import top.dext.bvc.client.audio.MicrophoneTest;
import top.dext.bvc.client.audio.RemoteSpeaker;

/**
 * Minecraft-free content of the in-game settings screen: a list of rows, each a label plus a
 * button that cycles or steps a value. Every Minecraft version just lays the rows out as
 * buttons and calls {@link Row#press}. Changes are applied and saved at once.
 */
public final class SettingsModel {

    /** Translation keys used by the rows; every mod's lang files define them. */
    public static final String TITLE = "gui.bettervoicechat.settings";

    /** One line of the settings screen. */
    public abstract static class Row {
        /** Translation key of the label. */
        public final String labelKey;

        Row(String labelKey) {
            this.labelKey = labelKey;
        }

        /** Text shown on the button (already human-readable, may contain a translation key, see {@link #valueKey}). */
        public abstract String value();

        /** If non-null, the button text is this translation key instead of {@link #value()}. */
        public String valueKey() {
            return null;
        }

        /** Left click: next / more. {@code backwards} = right click or shift: previous / less. */
        public abstract void press(boolean backwards);

        /** Literal label (player names), or {@code null} to translate {@link #labelKey}. */
        public String literalLabel() {
            return null;
        }
    }

    private final VoiceClient client;

    public SettingsModel(VoiceClient client) {
        this.client = client;
    }

    private ClientSettings settings() {
        return client.settings();
    }

    /** The rows, rebuilt each time the screen opens (devices and speakers change). */
    public List<Row> rows() {
        List<Row> rows = new ArrayList<Row>();
        rows.add(new Row("gui.bettervoicechat.activation") {
            @Override
            public String value() {
                return settings().activation().name();
            }

            @Override
            public String valueKey() {
                return "hud.bettervoicechat.activation." + settings().activation().name().toLowerCase(Locale.ROOT);
            }

            @Override
            public void press(boolean backwards) {
                ActivationMode[] modes = ActivationMode.values();
                int next = (settings().activation().ordinal() + (backwards ? modes.length - 1 : 1)) % modes.length;
                settings().setActivation(modes[next]);
                settings().save();
            }
        });
        rows.add(new Row("gui.bettervoicechat.threshold") {
            @Override
            public String value() {
                return Math.round(settings().voiceThresholdDb()) + " dB  (" + Math.round(client.status().microphoneLevelDb) + " dB)";
            }

            @Override
            public void press(boolean backwards) {
                settings().setVoiceThresholdDb(step(settings().voiceThresholdDb(), 5f, -90f, 0f, backwards));
                settings().save();
            }
        });
        rows.add(new Row("gui.bettervoicechat.input_gain") {
            @Override
            public String value() {
                return percent(settings().inputGain());
            }

            @Override
            public void press(boolean backwards) {
                settings().setInputGain(step(settings().inputGain(), 0.1f, 0f, 4f, backwards));
                settings().save();
            }
        });
        rows.add(new Row("gui.bettervoicechat.output_volume") {
            @Override
            public String value() {
                return percent(settings().outputVolume());
            }

            @Override
            public void press(boolean backwards) {
                settings().setOutputVolume(step(settings().outputVolume(), 0.1f, 0f, 2f, backwards));
                settings().save();
            }
        });
        rows.add(deviceRow("gui.bettervoicechat.input_device", true));
        rows.add(new Row("gui.bettervoicechat.mic_test") {
            @Override
            public String value() {
                return testText();
            }

            @Override
            public String valueKey() {
                return client.microphoneTest().isRunning() ? null : "gui.bettervoicechat.mic_test.start";
            }

            /** Forward: start with loopback (hear yourself) / stop. Back: start without loopback. */
            @Override
            public void press(boolean backwards) {
                if (client.microphoneTest().isRunning()) {
                    client.stopMicrophoneTest();
                } else {
                    client.startMicrophoneTest(!backwards);
                }
            }
        });
        rows.add(deviceRow("gui.bettervoicechat.output_device", false));
        rows.add(new Row("gui.bettervoicechat.hud") {
            @Override
            public String value() {
                return settings().hudEnabled() ? "ON" : "OFF";
            }

            @Override
            public String valueKey() {
                return settings().hudEnabled() ? "options.on" : "options.off";
            }

            @Override
            public void press(boolean backwards) {
                settings().setHudEnabled(!settings().hudEnabled());
                settings().save();
            }
        });
        for (final RemoteSpeaker speaker : client.knownSpeakers()) {
            final UUID uuid = speaker.uuid();
            if (uuid == null) {
                continue;
            }
            final String name = speaker.name() == null ? uuid.toString().substring(0, 8) : speaker.name();
            rows.add(new Row("gui.bettervoicechat.player") {
                @Override
                public String literalLabel() {
                    return name;
                }

                @Override
                public String value() {
                    return settings().isPlayerMuted(uuid) ? "-" : percent(settings().playerVolume(uuid));
                }

                @Override
                public String valueKey() {
                    return settings().isPlayerMuted(uuid) ? "gui.bettervoicechat.player_muted" : null;
                }

                /** Steps 0..200 %; one step below 0 mutes, above 200 % wraps back to unmuted 100 %. */
                @Override
                public void press(boolean backwards) {
                    if (settings().isPlayerMuted(uuid)) {
                        settings().setPlayerMuted(uuid, false);
                        settings().setPlayerVolume(uuid, backwards ? 2f : 0f);
                    } else {
                        float volume = settings().playerVolume(uuid);
                        if (backwards && volume <= 0.001f) {
                            settings().setPlayerMuted(uuid, true);
                        } else if (!backwards && volume >= 1.999f) {
                            settings().setPlayerMuted(uuid, true);
                        } else {
                            settings().setPlayerVolume(uuid, step(volume, 0.25f, 0f, 2f, backwards));
                        }
                    }
                    settings().save();
                }
            });
        }
        return rows;
    }

    private Row deviceRow(String key, final boolean input) {
        return new Row(key) {
            @Override
            public String value() {
                String current = input ? settings().inputDevice() : settings().outputDevice();
                return current.isEmpty() ? "Default" : shorten(current);
            }

            @Override
            public String valueKey() {
                String current = input ? settings().inputDevice() : settings().outputDevice();
                return current.isEmpty() ? "gui.bettervoicechat.default_device" : null;
            }

            @Override
            public void press(boolean backwards) {
                List<String> names = new ArrayList<String>();
                names.add("");
                names.addAll(input ? client.devices().inputDevices() : client.devices().outputDevices());
                String current = input ? settings().inputDevice() : settings().outputDevice();
                int index = Math.max(0, names.indexOf(current));
                String next = names.get((index + (backwards ? names.size() - 1 : 1)) % names.size());
                if (input) {
                    settings().setInputDevice(next);
                } else {
                    settings().setOutputDevice(next);
                }
                settings().save();
                client.reopenDevices();
                if (!input && client.microphoneTest().isRunning()) {
                    client.startMicrophoneTest(true); // loopback onto the new speakers
                }
            }
        };
    }

    /** Live result of the microphone test, e.g. "OK  -32 dB  ||||||". */
    private String testText() {
        MicrophoneTest test = client.microphoneTest();
        if (!test.isRunning() && test.state() != MicrophoneTest.State.FAILED) {
            return "";
        }
        switch (test.state()) {
            case STARTING:
                return "... " + Math.round(test.levelDb()) + " dB";
            case FAILED:
                return "X " + test.error();
            case SILENT:
                return "?? " + Math.round(test.levelDb()) + " dB (" + Math.round(test.peakDb()) + ")";
            default:
                int bars = Math.max(0, Math.min(10, Math.round((test.levelDb() + 70f) / 7f)));
                StringBuilder meter = new StringBuilder();
                for (int i = 0; i < 10; i++) {
                    meter.append(i < bars ? '|' : '.');
                }
                return "OK " + Math.round(test.levelDb()) + " dB " + meter;
        }
    }

    /** Status line for the screen (null if there is nothing to say), as a translation key. */
    public String testStatusKey() {
        MicrophoneTest test = client.microphoneTest();
        switch (test.state()) {
            case STARTING:
                return "gui.bettervoicechat.mic_test.listening";
            case WORKING:
                return test.isRunning() ? "gui.bettervoicechat.mic_test.working" : null;
            case SILENT:
                return "gui.bettervoicechat.mic_test.silent";
            case FAILED:
                return "gui.bettervoicechat.mic_test.failed";
            default:
                return null;
        }
    }

    /** Stops a running test; screens call this when they close. */
    public void close() {
        client.stopMicrophoneTest();
    }

    static float step(float value, float step, float min, float max, boolean backwards) {
        float next = Math.round((value + (backwards ? -step : step)) / step) * step;
        return Math.max(min, Math.min(max, next));
    }

    private static String percent(float value) {
        return Math.round(value * 100) + "%";
    }

    private static String shorten(String device) {
        String name = displayName(device);
        return name.length() > 28 ? name.substring(0, 27) + "…" : name;
    }

    /**
     * Java 8 on non-English Windows returns device names as system-charset bytes read as
     * Latin-1 ("Ö÷ÉùÒô" instead of "主声音"). Undo that for display; the raw name stays the key.
     */
    static String displayName(String device) {
        boolean latin1Only = true;
        boolean high = false;
        for (int i = 0; i < device.length(); i++) {
            char c = device.charAt(i);
            if (c > 0xFF) {
                latin1Only = false;
                break;
            }
            high |= c >= 0x80;
        }
        if (!latin1Only || !high) {
            return device;
        }
        // The OS code page (GBK on Chinese Windows), not file.encoding, which launchers set to UTF-8.
        java.nio.charset.Charset system;
        try {
            system = java.nio.charset.Charset.forName(System.getProperty("sun.jnu.encoding",
                    java.nio.charset.Charset.defaultCharset().name()));
        } catch (RuntimeException e) {
            return device;
        }
        if (system.name().startsWith("ISO-8859") || system.name().equals("UTF-8")) {
            return device;
        }
        String decoded = new String(device.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), system);
        // Windows cuts names at 31 bytes, which can split the last character in half.
        if (decoded.endsWith("\uFFFD")) {
            decoded = decoded.substring(0, decoded.length() - 1);
        }
        return decoded.indexOf('\uFFFD') >= 0 ? device : decoded;
    }
}
