package top.dext.bvc.proxy.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import top.dext.bvc.protocol.data.TransportPolicy;

/**
 * Immutable snapshot of {@code config.yml}. Reloading creates a new instance.
 */
public record BvcConfig(
        TransportPolicy transportPolicy,
        String streamHost,
        int streamPort,
        int connectTimeoutMillis,
        int maxPendingConnections,
        int authTimeoutMillis,
        int idleTimeoutSeconds,
        boolean requireMod,
        int modDetectTimeoutSeconds,
        String downloadUrl,
        boolean exemptBedrock,
        Set<String> requireModExemptServers,
        int bitrate,
        int maxStreamsPerListener,
        float duckingDecibels,
        List<Integer> priorityLevels,
        FallbackMode serversWithoutPlugin,
        Recording recording,
        Reports reports,
        Web web) {

    /** Rolling recording of everything that was forwarded. */
    public record Recording(boolean enabled, long maxBytes, int segmentMinutes) {
    }

    /** {@code /vreport} and its evidence. */
    public record Reports(boolean enabled, int beforeSeconds, int afterSeconds, int cooldownSeconds, int keepDays,
                          List<String> aliases) {
    }

    /** Built-in web panel. */
    public record Web(boolean enabled, String bind, int port, String publicUrl, int sessionHours) {
    }

    public static final String RESOURCE = "bvc-proxy-config.yml";

    /** What to do on sub-servers that do not run the BVC plugin. */
    public enum FallbackMode {
        DISABLED,
        /** Everyone on that sub-server hears each other. */
        FREE
    }

    public static BvcConfig load(Path file) throws IOException {
        return from(new ConfigNode(YamlFiles.loadOrCreate(file, RESOURCE), YamlFiles.loadResource(RESOURCE)));
    }

    /** Bundled defaults only; used by tests. */
    public static BvcConfig defaults() {
        return parse("");
    }

    /** YAML text overlaid on the bundled defaults; used by tests. */
    public static BvcConfig parse(String yaml) {
        try {
            return from(new ConfigNode(YamlFiles.parseString(yaml), YamlFiles.loadResource(RESOURCE)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static BvcConfig from(ConfigNode node) {
        Set<String> exempt = new LinkedHashSet<>();
        for (Object server : node.list("client.require-mod-exempt-servers")) {
            exempt.add(String.valueOf(server).toLowerCase(Locale.ROOT));
        }
        List<Integer> levels = new ArrayList<>();
        for (Object level : node.list("priority.levels")) {
            try {
                int value = Integer.parseInt(String.valueOf(level).trim());
                if (value > 0 && value <= 255) {
                    levels.add(value);
                }
            } catch (NumberFormatException ignored) {
                // skip junk entries
            }
        }
        levels.sort(Collections.reverseOrder());

        return new BvcConfig(
                node.enumValue("transport.policy", TransportPolicy.class, TransportPolicy.AUTO),
                node.string("transport.stream-host", ""),
                clamp(node.integer("transport.stream-port", 0), 0, 65535),
                clamp(node.integer("transport.connect-timeout-ms", 5000), 500, 60_000),
                clamp(node.integer("transport.max-pending-connections", 256), 1, 100_000),
                clamp(node.integer("transport.auth-timeout-ms", 5000), 500, 60_000),
                clamp(node.integer("transport.idle-timeout-seconds", 30), 10, 600),
                node.bool("client.require-mod", false),
                clamp(node.integer("client.mod-detect-timeout-seconds", 10), 2, 300),
                node.string("client.download-url", ""),
                node.bool("client.exempt-bedrock", true),
                Collections.unmodifiableSet(exempt),
                clamp(node.integer("audio.bitrate", 16000), 6000, 128_000),
                clamp(node.integer("audio.max-streams-per-listener", 8), 1, 64),
                (float) Math.max(0, node.decimal("audio.ducking-db", 12.0)),
                Collections.unmodifiableList(levels),
                node.enumValue("servers-without-plugin", FallbackMode.class, FallbackMode.DISABLED),
                new Recording(
                        node.bool("recording.enabled", true),
                        Math.max(16L << 20, node.longValue("recording.max-size-mb", 1024) << 20),
                        clamp(node.integer("recording.segment-minutes", 60), 5, 24 * 60)),
                new Reports(
                        node.bool("reports.enabled", true),
                        clamp(node.integer("reports.before-seconds", 300), 10, 3600),
                        clamp(node.integer("reports.after-seconds", 120), 0, 1800),
                        clamp(node.integer("reports.cooldown-seconds", 120), 0, 86_400),
                        clamp(node.integer("reports.keep-days", 90), 1, 3650),
                        aliases(node.list("reports.command-aliases"))),
                new Web(
                        node.bool("web.enabled", true),
                        node.string("web.bind", "127.0.0.1"),
                        clamp(node.integer("web.port", 8790), 0, 65535),
                        stripSlash(node.string("web.public-url", "")),
                        clamp(node.integer("web.session-hours", 12), 1, 24 * 30)));
    }

    private static List<String> aliases(List<Object> values) {
        List<String> out = new ArrayList<>();
        for (Object value : values) {
            String alias = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
            if (!alias.isEmpty() && !out.contains(alias)) {
                out.add(alias);
            }
        }
        if (out.isEmpty()) {
            out.add("vreport");
        }
        return Collections.unmodifiableList(out);
    }

    private static String stripSlash(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public boolean isRequireModExempt(String server) {
        return server != null && requireModExemptServers.contains(server.toLowerCase(Locale.ROOT));
    }
}
