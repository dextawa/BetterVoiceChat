package top.dext.bvc.proxy.config;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Typed read access to a YAML tree, falling back to the bundled defaults for missing keys.
 */
final class ConfigNode {

    private final Map<String, Object> values;
    private final Map<String, Object> defaults;

    ConfigNode(Map<String, Object> values, Map<String, Object> defaults) {
        this.values = values == null ? Collections.<String, Object>emptyMap() : values;
        this.defaults = defaults == null ? Collections.<String, Object>emptyMap() : defaults;
    }

    private Object raw(String path) {
        Object value = lookup(values, path);
        return value != null ? value : lookup(defaults, path);
    }

    @SuppressWarnings("unchecked")
    private static Object lookup(Map<String, Object> root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(part);
        }
        return current;
    }

    String string(String path, String fallback) {
        Object value = raw(path);
        return value == null ? fallback : String.valueOf(value);
    }

    int integer(String path, int fallback) {
        Object value = raw(path);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    long longValue(String path, long fallback) {
        Object value = raw(path);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    double decimal(String path, double fallback) {
        Object value = raw(path);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    boolean bool(String path, boolean fallback) {
        Object value = raw(path);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value).trim());
    }

    @SuppressWarnings("unchecked")
    List<Object> list(String path) {
        Object value = raw(path);
        return value instanceof List ? (List<Object>) value : Collections.emptyList();
    }

    <E extends Enum<E>> E enumValue(String path, Class<E> type, E fallback) {
        String text = string(path, null);
        if (text == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
