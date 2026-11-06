package top.dext.bvc.backend.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the YAML subset {@code config.yml} uses: nested maps by indentation, {@code - item} and
 * {@code [a, b]} lists, {@code {}}, quoted or plain scalars and {@code #} comments. Server mods
 * have no YAML library, so the plugin and the mods share this one.
 */
final class MiniYaml {

    private final List<String> lines = new ArrayList<String>();
    private final List<Integer> indents = new ArrayList<Integer>();
    private int pos;

    private MiniYaml(String text) {
        for (String raw : text.replace("\r", "").split("\n")) {
            String line = stripComment(raw.replace('\t', ' '));
            if (line.trim().isEmpty()) {
                continue;
            }
            int indent = 0;
            while (line.charAt(indent) == ' ') {
                indent++;
            }
            lines.add(line.trim());
            indents.add(indent);
        }
    }

    static Map<String, Object> parse(String text) {
        MiniYaml yaml = new MiniYaml(text);
        if (yaml.lines.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        Object root = yaml.block(yaml.indents.get(0));
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("The top level must be a map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) root;
        return map;
    }

    /** A map or list whose entries all start at {@code indent}. */
    private Object block(int indent) {
        if (lines.get(pos).startsWith("- ") || lines.get(pos).equals("-")) {
            List<Object> list = new ArrayList<Object>();
            while (pos < lines.size() && indents.get(pos) == indent && lines.get(pos).startsWith("-")) {
                String item = lines.get(pos).substring(1).trim();
                pos++;
                list.add(item.isEmpty() ? child(indent) : scalar(item));
            }
            return list;
        }
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        while (pos < lines.size() && indents.get(pos) == indent) {
            String line = lines.get(pos);
            int colon = keyColon(line);
            if (colon < 0) {
                throw new IllegalArgumentException("Expected 'key: value' but found '" + line + "'");
            }
            String key = unquote(line.substring(0, colon).trim());
            String value = line.substring(colon + 1).trim();
            pos++;
            map.put(key, value.isEmpty() ? child(indent) : scalar(value));
        }
        if (pos < lines.size() && indents.get(pos) > indent) {
            throw new IllegalArgumentException("Unexpected indentation at '" + lines.get(pos) + "'");
        }
        return map;
    }

    /** The nested block after {@code key:}, or {@code null} if there is none. */
    private Object child(int parentIndent) {
        if (pos < lines.size() && indents.get(pos) > parentIndent) {
            return block(indents.get(pos));
        }
        // "key:" followed by "- item" at the same indent is also a list in YAML.
        if (pos < lines.size() && indents.get(pos) == parentIndent && lines.get(pos).startsWith("- ")) {
            return block(parentIndent);
        }
        return null;
    }

    private static Object scalar(String text) {
        if (text.equals("{}")) {
            return new LinkedHashMap<String, Object>();
        }
        if (text.startsWith("[") && text.endsWith("]")) {
            List<Object> list = new ArrayList<Object>();
            String body = text.substring(1, text.length() - 1).trim();
            if (!body.isEmpty()) {
                for (String item : body.split(",")) {
                    list.add(scalar(item.trim()));
                }
            }
            return list;
        }
        if (text.startsWith("\"") || text.startsWith("'")) {
            return unquote(text);
        }
        if (text.equals("true") || text.equals("false")) {
            return Boolean.valueOf(text);
        }
        if (text.equals("null") || text.equals("~")) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            try {
                return Double.valueOf(text);
            } catch (NumberFormatException ignored) {
                return text;
            }
        }
    }

    private static String unquote(String text) {
        if (text.length() >= 2 && (text.charAt(0) == '"' || text.charAt(0) == '\'')
                && text.charAt(text.length() - 1) == text.charAt(0)) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /** The colon ending the key, outside quotes and followed by a space or the line end. */
    private static int keyColon(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ':' && (i + 1 == line.length() || line.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#' && (i == 0 || line.charAt(i - 1) == ' ')) {
                return line.substring(0, i);
            }
        }
        return line;
    }
}
