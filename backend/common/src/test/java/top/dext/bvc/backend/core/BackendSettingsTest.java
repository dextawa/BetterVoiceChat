package top.dext.bvc.backend.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import top.dext.bvc.api.server.Activation;
import top.dext.bvc.api.server.VoiceMode;
import top.dext.bvc.backend.voice.SpectatorPolicy;

class BackendSettingsTest {

    private final List<String> warnings = new ArrayList<>();
    private final BackendLog log = new BackendLog() {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        @Override
        public void error(String message, Throwable error) {
            warnings.add(message);
        }
    };

    private BackendSettings read(String yaml) {
        return BackendSettings.read(MiniYaml.parse(yaml), log);
    }

    @Test
    void bundledConfigReadsWithoutWarnings() throws IOException {
        String yaml;
        try (InputStream in = BvcBackend.class.getResourceAsStream("config.yml")) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        BackendSettings settings = read(yaml);
        assertEquals(VoiceMode.PROXIMITY, settings.rules.defaultZone.mode());
        assertEquals(Activation.ANY, settings.rules.defaultZone.activation());
        assertEquals(48, settings.rules.defaultZone.radius());
        assertTrue(settings.rules.worldZones.isEmpty());
        assertTrue(settings.rules.scoreboardTeams);
        assertEquals(SpectatorPolicy.SAME, settings.rules.spectators);
        assertEquals(Arrays.asList(200, 100, 50, 10), settings.priorityLevels);
        assertEquals(2, settings.intervalTicks);
        assertEquals(5, settings.fullResyncSeconds);
        assertEquals(List.of(), warnings);
    }

    @Test
    void worldZonesInheritFromTheDefaultZone() {
        BackendSettings settings = read(String.join("\n",
                "zone:",
                "  mode: PROXIMITY",
                "  activation: PTT_ONLY   # comment after a value",
                "  radius: 32",
                "worlds:",
                "  \"minecraft:the_nether\":",
                "    mode: FREE",
                "  arena:",
                "    radius: 10.5",
                "teams:",
                "  use-scoreboard: false",
                "spectators: listen_only",
                "priority-levels:",
                "  - 5",
                "  - 300",
                "  - 90"));
        assertEquals(VoiceMode.FREE, settings.rules.worldZones.get("minecraft:the_nether").mode());
        assertEquals(Activation.PTT_ONLY, settings.rules.worldZones.get("minecraft:the_nether").activation());
        assertEquals(32, settings.rules.worldZones.get("minecraft:the_nether").radius());
        assertEquals(VoiceMode.PROXIMITY, settings.rules.worldZones.get("arena").mode());
        assertEquals(10.5, settings.rules.worldZones.get("arena").radius());
        assertFalse(settings.rules.scoreboardTeams);
        assertEquals(SpectatorPolicy.LISTEN_ONLY, settings.rules.spectators);
        assertEquals(Arrays.asList(90, 5), settings.priorityLevels);
        assertEquals(List.of(), warnings);
    }

    @Test
    void invalidValuesFallBackWithAWarning() {
        BackendSettings settings = read("zone:\n  mode: LOUD\nupdate-interval-ticks: 999\n");
        assertEquals(VoiceMode.PROXIMITY, settings.rules.defaultZone.mode());
        assertEquals(40, settings.intervalTicks);
        assertEquals(1, warnings.size());
    }

    @Test
    void parsesFlowListsAndEmptyMaps() {
        Map<String, Object> map = MiniYaml.parse("a: [1, 2, 'x y']\nb: {}\nc: 'q: # not a comment'\n");
        assertEquals(Arrays.asList(1, 2, "x y"), map.get("a"));
        assertTrue(((Map<?, ?>) map.get("b")).isEmpty());
        assertEquals("q: # not a comment", map.get("c"));
    }
}
