package top.dext.bvc.backend.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import top.dext.bvc.api.server.Activation;
import top.dext.bvc.api.server.VoiceMode;
import top.dext.bvc.api.server.ZoneSettings;
import top.dext.bvc.backend.voice.SpectatorPolicy;
import top.dext.bvc.backend.voice.VoiceRules;

/**
 * Typed view of the sub-server {@code config.yml}. Immutable.
 */
public final class BackendSettings {

    public final VoiceRules rules;
    /** Descending. */
    public final List<Integer> priorityLevels;
    public final int intervalTicks;
    public final int fullResyncSeconds;

    private BackendSettings(VoiceRules rules, List<Integer> priorityLevels, int intervalTicks, int fullResyncSeconds) {
        this.rules = rules;
        this.priorityLevels = priorityLevels;
        this.intervalTicks = intervalTicks;
        this.fullResyncSeconds = fullResyncSeconds;
    }

    /** {@code config} is the parsed YAML: nested maps, lists and scalars. */
    public static BackendSettings read(Map<String, Object> config, BackendLog logger) {
        ZoneSettings defaultZone = zone(section(config, "zone"), ZoneSettings.of(VoiceMode.PROXIMITY), "zone", logger);

        Map<String, ZoneSettings> worlds = new LinkedHashMap<String, ZoneSettings>();
        Map<String, Object> worldSection = section(config, "worlds");
        if (worldSection != null) {
            for (String world : worldSection.keySet()) {
                worlds.put(world, zone(section(worldSection, world), defaultZone, "worlds." + world, logger));
            }
        }

        SpectatorPolicy spectators = parse(SpectatorPolicy.class, string(config, "spectators"), SpectatorPolicy.SAME,
                "spectators", logger);
        Map<String, Object> teams = section(config, "teams");
        boolean scoreboardTeams = teams == null || !"false".equalsIgnoreCase(string(teams, "use-scoreboard"));

        List<Integer> levels = new ArrayList<Integer>();
        Object levelList = config.get("priority-levels");
        if (levelList instanceof List) {
            for (Object item : (List<?>) levelList) {
                Integer level = integer(item);
                if (level != null && level > 0 && level <= 255) {
                    levels.add(level);
                }
            }
        }
        Collections.sort(levels, Collections.<Integer>reverseOrder());

        int interval = Math.max(1, Math.min(40, integer(config.get("update-interval-ticks"), 2)));
        int resync = Math.max(1, Math.min(300, integer(config.get("full-resync-seconds"), 5)));
        return new BackendSettings(new VoiceRules(defaultZone, worlds, spectators, scoreboardTeams),
                Collections.unmodifiableList(levels), interval, resync);
    }

    /** Missing keys inherit from {@code parent}, so a world can override only its mode. */
    private static ZoneSettings zone(Map<String, Object> section, ZoneSettings parent, String path, BackendLog logger) {
        if (section == null) {
            return parent;
        }
        VoiceMode mode = parse(VoiceMode.class, string(section, "mode"), parent.mode(), path + ".mode", logger);
        Activation activation = parse(Activation.class, string(section, "activation"), parent.activation(),
                path + ".activation", logger);
        Object radius = section.get("radius");
        double value = parent.radius();
        if (radius != null) {
            try {
                value = Math.max(0, Double.parseDouble(radius.toString().trim()));
            } catch (NumberFormatException e) {
                logger.warn("Invalid value '" + radius + "' for " + path + ".radius, using " + parent.radius());
            }
        }
        return new ZoneSettings(mode, activation, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private static String string(Map<String, Object> section, String key) {
        Object value = section.get(key);
        return value == null ? null : value.toString();
    }

    private static Integer integer(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignored) {
                // falls through
            }
        }
        return null;
    }

    private static int integer(Object value, int fallback) {
        Integer parsed = integer(value);
        return parsed == null ? fallback : parsed;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String text, E fallback, String path, BackendLog logger) {
        if (text == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid value '" + text + "' for " + path + ", using " + fallback);
            return fallback;
        }
    }
}
