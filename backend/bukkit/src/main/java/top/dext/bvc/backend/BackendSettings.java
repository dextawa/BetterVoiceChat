package top.dext.bvc.backend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import top.dext.bvc.api.server.Activation;
import top.dext.bvc.api.server.VoiceMode;
import top.dext.bvc.api.server.ZoneSettings;
import top.dext.bvc.backend.voice.SpectatorPolicy;
import top.dext.bvc.backend.voice.VoiceRules;

/**
 * Typed view of the sub-server {@code config.yml}. Immutable.
 */
final class BackendSettings {

    final VoiceRules rules;
    /** Descending. */
    final List<Integer> priorityLevels;
    final int intervalTicks;
    final int fullResyncSeconds;

    private BackendSettings(VoiceRules rules, List<Integer> priorityLevels, int intervalTicks, int fullResyncSeconds) {
        this.rules = rules;
        this.priorityLevels = priorityLevels;
        this.intervalTicks = intervalTicks;
        this.fullResyncSeconds = fullResyncSeconds;
    }

    static BackendSettings read(FileConfiguration config, Logger logger) {
        ZoneSettings defaultZone = zone(config.getConfigurationSection("zone"), ZoneSettings.of(VoiceMode.PROXIMITY), "zone", logger);

        Map<String, ZoneSettings> worlds = new LinkedHashMap<String, ZoneSettings>();
        ConfigurationSection worldSection = config.getConfigurationSection("worlds");
        if (worldSection != null) {
            for (String world : worldSection.getKeys(false)) {
                worlds.put(world, zone(worldSection.getConfigurationSection(world), defaultZone, "worlds." + world, logger));
            }
        }

        SpectatorPolicy spectators = parse(SpectatorPolicy.class, config.getString("spectators"), SpectatorPolicy.SAME,
                "spectators", logger);
        boolean scoreboardTeams = config.getBoolean("teams.use-scoreboard", true);

        List<Integer> levels = new ArrayList<Integer>();
        for (Integer level : config.getIntegerList("priority-levels")) {
            if (level != null && level > 0 && level <= 255) {
                levels.add(level);
            }
        }
        Collections.sort(levels, Collections.<Integer>reverseOrder());

        int interval = Math.max(1, Math.min(40, config.getInt("update-interval-ticks", 2)));
        int resync = Math.max(1, Math.min(300, config.getInt("full-resync-seconds", 5)));
        return new BackendSettings(new VoiceRules(defaultZone, worlds, spectators, scoreboardTeams),
                Collections.unmodifiableList(levels), interval, resync);
    }

    /** Missing keys inherit from {@code parent}, so a world can override only its mode. */
    private static ZoneSettings zone(ConfigurationSection section, ZoneSettings parent, String path, Logger logger) {
        if (section == null) {
            return parent;
        }
        VoiceMode mode = parse(VoiceMode.class, section.getString("mode"), parent.mode(), path + ".mode", logger);
        Activation activation = parse(Activation.class, section.getString("activation"), parent.activation(),
                path + ".activation", logger);
        double radius = section.contains("radius") ? Math.max(0, section.getDouble("radius")) : parent.radius();
        return new ZoneSettings(mode, activation, radius);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String text, E fallback, String path, Logger logger) {
        if (text == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warning("Invalid value '" + text + "' for " + path + ", using " + fallback);
            return fallback;
        }
    }
}
