package com.heimdall.core.config;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.AtomicFiles;
import com.heimdall.core.util.Strings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads and writes {@code bootstrap.yml}.
 *
 * <p>Three properties this has that a plain "parse the YAML into fields" would not:
 *
 * <ul>
 *   <li><strong>A missing file is a state, not an error.</strong> {@link #exists()} is {@code
 *       false} and {@link #load()} hands back {@link BootstrapConfig#defaults()}, whose {@link
 *       BootstrapConfig#isConfigured()} is {@code false}. That is what drives the setup flow — a
 *       fresh install is the normal case, not a failure to be logged as one.
 *   <li><strong>Unknown keys survive a round trip.</strong> The parsed map is retained and merged
 *       back on save, so a newer plugin version writing a key this one has never heard of does not
 *       lose it when this version saves. Rolling a fleet back a version is otherwise silently
 *       destructive.
 *   <li><strong>Saves are atomic.</strong> See {@link AtomicFiles} — a half-written bootstrap file
 *       means a server that cannot find the bot at all on its next boot.
 * </ul>
 *
 * <p>A malformed file is reported and treated as absent rather than thrown: the plugin has to boot
 * far enough to tell an operator what is wrong with their config.
 *
 * <p>Comments in a hand-edited file are <em>not</em> preserved across a save — SnakeYAML discards
 * them at parse time. Saves come from the setup flow, which writes a file that did not exist or
 * that it wrote itself, so this trades a rare cosmetic loss for not carrying a comment-preserving
 * YAML editor around.
 */
public final class BootstrapStore {

    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_TOKEN_ID = "tokenId";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_SERVER_ID = "serverId";
    private static final String KEY_ROLE = "role";
    private static final String KEY_DEBUG = "debug";

    private final HeimdallLogger logger;
    private final Path file;

    /** Keys read from disk that this version does not know about, kept for the next save. */
    private final Map<String, Object> passthrough = new LinkedHashMap<String, Object>();

    public BootstrapStore(HeimdallLogger logger, Path file) {
        if (logger == null || file == null) {
            throw new IllegalArgumentException("logger and file are required");
        }
        this.logger = logger;
        this.file = file;
    }

    /** The file this store reads and writes. */
    public Path file() {
        return file;
    }

    /** Whether {@code bootstrap.yml} is present. {@code false} means the setup flow has not run. */
    public boolean exists() {
        return Files.isRegularFile(file);
    }

    /**
     * Reads the file, filling in defaults for anything missing.
     *
     * <p>Never throws. A missing, empty, unreadable or malformed file yields {@link
     * BootstrapConfig#defaults()}; the latter two are logged.
     */
    public BootstrapConfig load() {
        passthrough.clear();
        if (!exists()) {
            return BootstrapConfig.defaults();
        }

        Map<String, Object> raw;
        try {
            raw = readYaml();
        } catch (IOException e) {
            logger.error("Could not read " + file + " — treating this server as unconfigured", e);
            return BootstrapConfig.defaults();
        } catch (RuntimeException e) {
            logger.error("Could not parse " + file + " — treating this server as unconfigured", e);
            return BootstrapConfig.defaults();
        }

        BootstrapConfig.Builder builder = BootstrapConfig.builder();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (KEY_ENDPOINT.equals(key)) {
                builder.endpoint(asString(value));
            } else if (KEY_TOKEN_ID.equals(key)) {
                builder.tokenId(asString(value));
            } else if (KEY_TOKEN.equals(key)) {
                builder.token(asString(value));
            } else if (KEY_SERVER_ID.equals(key)) {
                builder.serverId(asString(value));
            } else if (KEY_ROLE.equals(key)) {
                builder.role(parseRole(asString(value)));
            } else if (KEY_DEBUG.equals(key)) {
                builder.debug(asBoolean(value));
            } else {
                passthrough.put(key, value);
            }
        }

        if (!passthrough.isEmpty()) {
            logger.debug("bootstrap.yml carries " + passthrough.size()
                    + " key(s) this version does not use; they will be preserved on save");
        }
        return builder.build();
    }

    /**
     * Writes the config, preserving any unknown keys seen by the most recent {@link #load()}.
     *
     * @throws IOException if the file could not be written
     */
    public void save(BootstrapConfig config) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put(KEY_ENDPOINT, config.endpoint());
        document.put(KEY_TOKEN_ID, config.tokenId());
        document.put(KEY_TOKEN, config.token());
        document.put(KEY_SERVER_ID, config.serverId());
        document.put(KEY_ROLE, config.role().wireName());
        document.put(KEY_DEBUG, Boolean.valueOf(config.debug()));
        document.putAll(passthrough);

        AtomicFiles.writeUtf8(file, dumper().dump(document));
        logger.debug(() -> "Wrote " + file + " (" + config + ")");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYaml() throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            Object loaded = loader().load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            if (loaded == null) {
                // An empty or all-comments file: legal YAML, no content.
                return new LinkedHashMap<String, Object>();
            }
            if (!(loaded instanceof Map)) {
                logger.warn(file + " does not contain a YAML mapping — ignoring its contents");
                return new LinkedHashMap<String, Object>();
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) loaded).entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
    }

    /**
     * A parser that cannot instantiate arbitrary classes.
     *
     * <p>{@code SafeConstructor} rather than the default: {@code bootstrap.yml} lives in a
     * directory a server operator's other plugins (and their FTP clients) can write to, and
     * SnakeYAML's default constructor will build any class the document names.
     */
    private static Yaml loader() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    private static Yaml dumper() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return new Yaml(options);
    }

    private ServerRole parseRole(String raw) {
        ServerRole parsed = ServerRole.parse(raw, null);
        if (parsed == null) {
            if (Strings.isNotBlank(raw)) {
                logger.warn("Unknown role '" + raw + "' in " + file + " — falling back to '"
                        + ServerRole.AUTO.wireName() + "'");
            }
            return ServerRole.AUTO;
        }
        return parsed;
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Reads a boolean the way a hand-edited config demands: SnakeYAML already turns {@code true} /
     * {@code yes} / {@code on} into a {@link Boolean}, but a quoted {@code "true"} arrives as a
     * String and would otherwise silently mean {@code false}.
     */
    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text);
    }
}
