package top.dext.bvc.proxy.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Player-facing texts from {@code messages.yml}, with {@code {placeholder}} substitution.
 */
public final class Messages {

    public static final String RESOURCE = "bvc-proxy-messages.yml";

    private final ConfigNode node;

    private Messages(ConfigNode node) {
        this.node = node;
    }

    public static Messages load(Path file) throws IOException {
        return new Messages(new ConfigNode(YamlFiles.loadOrCreate(file, RESOURCE), YamlFiles.loadResource(RESOURCE)));
    }

    public static Messages defaults() {
        try {
            return new Messages(new ConfigNode(null, YamlFiles.loadResource(RESOURCE)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Raw text with placeholders replaced, no prefix. {@code replacements} = key, value, key, value... */
    public String raw(String key, Object... replacements) {
        return format(node.string(key, key), replacements);
    }

    /** Text with the configured prefix, for chat. */
    public String get(String key, Object... replacements) {
        return node.string("prefix", "") + raw(key, replacements);
    }

    public List<String> lines(String key) {
        List<Object> values = node.list(key);
        List<String> lines = new ArrayList<>(values.size());
        for (Object value : values) {
            lines.add(String.valueOf(value));
        }
        return Collections.unmodifiableList(lines);
    }

    static String format(String template, Object... replacements) {
        if (replacements.length == 0) {
            return template;
        }
        StringBuilder out = new StringBuilder(template);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            String token = "{" + replacements[i] + "}";
            String value = String.valueOf(replacements[i + 1]);
            int index = out.indexOf(token);
            while (index >= 0) {
                out.replace(index, index + token.length(), value);
                index = out.indexOf(token, index + value.length());
            }
        }
        return out.toString();
    }
}
