package top.dext.bvc.proxy.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads YAML files from the data folder, creating them from bundled resources on first start.
 */
public final class YamlFiles {

    private YamlFiles() {
    }

    public static Map<String, Object> loadResource(String resource) throws IOException {
        try (InputStream in = YamlFiles.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing bundled resource " + resource);
            }
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /** Reads {@code file}, copying {@code resource} there first if it does not exist. */
    public static Map<String, Object> loadOrCreate(Path file, String resource) throws IOException {
        if (Files.notExists(file)) {
            Files.createDirectories(file.getParent());
            try (InputStream in = YamlFiles.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("Missing bundled resource " + resource);
                }
                Files.copy(in, file);
            }
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    public static Map<String, Object> parseString(String yaml) {
        return parse(new java.io.StringReader(yaml));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(Reader reader) {
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        return loaded instanceof Map ? (Map<String, Object>) loaded : null;
    }
}
