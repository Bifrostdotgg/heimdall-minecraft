package com.heimdall.core.migrate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.heimdall.core.log.HeimdallLogger;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Turns a v2 config file on disk into a {@link V2Config}, or into {@code null}.
 *
 * <h2>Two formats, one tree</h2>
 *
 * <p>v2's Bukkit build read YAML and its Velocity build read JSON, through two unrelated
 * {@code ConfigProvider} implementations. Both are loaded here into the same nested
 * {@code Map<String, Object>} — the shape SnakeYAML already produces — so {@link V2Config} has one
 * interpretation of every key rather than one per platform. The JSON side is normalised into that
 * shape by {@link #toTree}: objects become maps, arrays become lists, and primitives become the
 * {@code String} / {@code Boolean} / {@code Number} SnakeYAML would have produced for the same
 * literal.
 *
 * <h2>{@code null} means "I could not read this at all"</h2>
 *
 * <p>Everything softer than that is absorbed by {@link V2Config}'s defaults. A missing key, a
 * wrong-typed key, a half-finished file, a file whose root is a list or a bare string — all of those
 * parse, and produce a config that answers v2's defaults for whatever was not there.
 *
 * <p>{@code null} is reserved for the two cases where there is genuinely no document: the file could
 * not be read, or the parser refused it outright. That distinction is the whole reason this returns
 * a nullable rather than always handing back a defaults-only config. The two states need different
 * things said to the operator — "your v2 config is not valid YAML, fix it or delete it" versus "your
 * v2 config has no API key in it" — and a defaults-only config is indistinguishable from a real file
 * that happens to be empty. Getting that wrong means renaming somebody's broken config away and
 * telling them nothing about why.
 *
 * <p>Neither case throws. The plugin has to boot far enough to log the sentence above.
 *
 * <h2>The YAML parser cannot instantiate classes</h2>
 *
 * <p>{@code SafeConstructor}, for the same reason {@code BootstrapStore} uses it: this file lives in
 * a directory the operator's other plugins and their FTP clients can write to, and SnakeYAML's
 * default constructor will build any class the document names.
 */
public final class V2ConfigReader {

    /** v2's Bukkit config file name. */
    public static final String YAML_FILE_NAME = "config.yml";

    /** v2's Velocity config file name. */
    public static final String JSON_FILE_NAME = "config.json";

    private final HeimdallLogger logger;

    public V2ConfigReader(HeimdallLogger logger) {
        if (logger == null) {
            throw new IllegalArgumentException("logger is required");
        }
        this.logger = logger;
    }

    /**
     * Reads a v2 config, choosing the parser from the file's extension.
     *
     * <p>Anything that is not {@code .json} is read as YAML. That is the forgiving direction: YAML is
     * a superset of JSON, so a {@code config.yml} that somebody filled with JSON still parses, while
     * the reverse does not hold.
     *
     * @return the parsed config, or {@code null} if the file could not be read or parsed
     */
    public V2Config read(Path file) {
        if (file == null) {
            return null;
        }
        String name = file.getFileName() == null
                ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".json") ? readJson(file) : readYaml(file);
    }

    /**
     * Reads a v2 Bukkit {@code config.yml}.
     *
     * @return the parsed config, or {@code null} if the file could not be read or parsed
     */
    public V2Config readYaml(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            return V2Config.of(loaded instanceof Map ? asStringKeyedMap((Map<?, ?>) loaded) : null);
        } catch (IOException e) {
            logger.warn("Could not read the v2 config at " + file + ": " + e.getMessage());
            return null;
        } catch (RuntimeException e) {
            // SnakeYAML signals every parse failure as an unchecked YAMLException subclass, and the
            // catch is deliberately wider than that: this runs on the boot path, and no malformed
            // file an operator can write is allowed to prevent the plugin starting.
            logger.warn("Could not parse the v2 config at " + file + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Reads a v2 Velocity {@code config.json}.
     *
     * @return the parsed config, or {@code null} if the file could not be read or parsed
     */
    public V2Config readJson(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                // Legal JSON, but not a document with settings in it. Defaults, not a failure —
                // exactly what the YAML side does for a root that is not a mapping.
                return V2Config.of(null);
            }
            return V2Config.of(toTree(parsed.getAsJsonObject()));
        } catch (IOException e) {
            logger.warn("Could not read the v2 config at " + file + ": " + e.getMessage());
            return null;
        } catch (JsonParseException e) {
            logger.warn("Could not parse the v2 config at " + file + ": " + e.getMessage());
            return null;
        } catch (RuntimeException e) {
            // Gson's reader can also surface a malformed document as an IllegalStateException or a
            // NumberFormatException depending on where it gave up. Same treatment.
            logger.warn("Could not parse the v2 config at " + file + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Gson's object model as the map-and-list tree SnakeYAML would have produced.
     *
     * <p>JSON {@code null} is dropped rather than stored, so an explicit {@code "guildId": null} and
     * an absent {@code guildId} reach {@link V2Config} as the same thing. They mean the same thing to
     * an operator, and keeping the difference would only give the accessors a second empty case to
     * get wrong.
     */
    private static Map<String, Object> toTree(JsonObject object) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            Object value = toValue(entry.getValue());
            if (value != null) {
                out.put(entry.getKey(), value);
            }
        }
        return out;
    }

    private static Object toValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            return toTree(element.getAsJsonObject());
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            List<Object> out = new ArrayList<Object>(array.size());
            for (JsonElement child : array) {
                Object value = toValue(child);
                if (value != null) {
                    out.add(value);
                }
            }
            return out;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return Boolean.valueOf(primitive.getAsBoolean());
        }
        if (primitive.isNumber()) {
            // Gson hands back a LazilyParsedNumber, whose toString is the literal text. V2Config
            // reads numbers through BigDecimal, so keeping it as a Number rather than flattening it
            // to a double preserves a 64-bit id written as a JSON number.
            return primitive.getAsNumber();
        }
        return primitive.getAsString();
    }

    /** SnakeYAML keys can be any scalar; every key this reads is addressed by name. */
    private static Map<String, Object> asStringKeyedMap(Map<?, ?> loaded) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : loaded.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }
}
