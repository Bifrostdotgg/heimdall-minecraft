package com.heimdall.core.config;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.AtomicFiles;
import com.heimdall.core.util.Strings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
 * <p><strong>Comments in a hand-edited file are lost on save</strong>, because SnakeYAML discards
 * them at parse time. The justification this used to carry — "saves come from the setup flow, which
 * only ever rewrites a file it wrote itself" — is still not the whole story now that {@code /hd
 * setup} exists: a v2 migration writes this file too, guild discovery saves over it unprompted the
 * moment it answers, and plenty of installs were hand-written before any of that.
 *
 * <p>It is still the right trade, for a different reason: the alternative is carrying a
 * comment-preserving YAML editor to protect four keys and a cache. What the loss is not allowed to
 * be is <em>surprising</em>, so the one field the plugin writes on its own initiative is named
 * {@link #KEY_GUILD_ID_CACHE} rather than something an operator might take for a setting — the name
 * says what it is, on disk, without needing a comment that would not survive anyway.
 *
 * <p>Unknown keys <em>are</em> preserved (departure D19); only the comments around them are not.
 */
public final class BootstrapStore {

    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_TOKEN_ID = "tokenId";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_SERVER_ID = "serverId";
    private static final String KEY_ROLE = "role";
    private static final String KEY_DEBUG = "debug";
    private static final String KEY_TIMEOUT_MS = "timeoutMs";
    private static final String KEY_RETRIES = "retries";
    private static final String KEY_RETRY_DELAY_MS = "retryDelayMs";
    private static final String KEY_UPDATES_CHECK = "updatesCheckEnabled";
    private static final String KEY_UPDATES_NOTIFY = "updatesNotifyAdmins";
    private static final String KEY_UPDATES_INTERVAL = "updatesCheckIntervalHours";
    private static final String KEY_DISABLED_MODULES = "disabledModules";

    /**
     * A <strong>cache</strong>, written by the plugin, not a setting an operator fills in.
     *
     * <p>The key is spelled {@code guildIdCache} on disk, and the suffix is doing real work. Comments
     * do not survive a save (see the class javadoc), so the name is the only place the file itself
     * can say "this is not yours to set" — and the failure it prevents is specific: an operator who
     * read a bare {@code guildId} as configuration and corrected it to the guild they meant would
     * produce a server that signs perfectly and reads somebody else's configuration, which is the
     * exact hazard departure D54 removed the setting for.
     *
     * <p>It lives in this file rather than beside it because it belongs to the same identity the
     * token does: copy {@code bootstrap.yml} to another box and the guild it names is still right.
     * {@code identify} answers it on every boot, and whatever that answers overwrites this.
     */
    private static final String KEY_GUILD_ID_CACHE = "guildIdCache";

    /**
     * What the same cache was called before it was renamed.
     *
     * <p>Read, never written. 1d shipped the key as {@code guildId} for exactly as long as it took a
     * review to point out that the name reads like a setting, and any server that booted against
     * that build has one on disk. Dropping it would cost those servers one extra {@code identify}
     * per boot, which is harmless — but it would also mean silently discarding a key the plugin
     * itself wrote, and "the plugin deleted something out of my config" is a worse thing to be true
     * than a one-line migration.
     */
    private static final String LEGACY_KEY_GUILD_ID = "guildId";

    /** Every key this version knows how to interpret. Anything else is passed through untouched. */
    private static final Set<String> KNOWN_KEYS = new HashSet<String>(Arrays.asList(
            KEY_ENDPOINT, KEY_TOKEN_ID, KEY_TOKEN, KEY_SERVER_ID, KEY_ROLE, KEY_DEBUG,
            KEY_TIMEOUT_MS, KEY_RETRIES, KEY_RETRY_DELAY_MS,
            KEY_UPDATES_CHECK, KEY_UPDATES_NOTIFY, KEY_UPDATES_INTERVAL, KEY_DISABLED_MODULES,
            KEY_GUILD_ID_CACHE, LEGACY_KEY_GUILD_ID));

    private final HeimdallLogger logger;
    private final Path file;

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
    public synchronized BootstrapConfig load() {
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
            } else if (KEY_TIMEOUT_MS.equals(key)) {
                builder.timeoutMs((int) asLong(value, BootstrapConfig.DEFAULT_TIMEOUT_MS));
            } else if (KEY_RETRIES.equals(key)) {
                builder.retries((int) asLong(value, BootstrapConfig.DEFAULT_RETRIES));
            } else if (KEY_RETRY_DELAY_MS.equals(key)) {
                builder.retryDelayMs((int) asLong(value, BootstrapConfig.DEFAULT_RETRY_DELAY_MS));
            } else if (KEY_UPDATES_CHECK.equals(key)) {
                builder.updatesCheckEnabled(asBooleanOr(value, true));
            } else if (KEY_UPDATES_NOTIFY.equals(key)) {
                builder.updatesNotifyAdmins(asBooleanOr(value, true));
            } else if (KEY_UPDATES_INTERVAL.equals(key)) {
                builder.updatesCheckIntervalHours(
                        asLong(value, BootstrapConfig.DEFAULT_UPDATE_INTERVAL_HOURS));
            } else if (KEY_DISABLED_MODULES.equals(key)) {
                builder.disabledModules(asString(value));
            } else if (KEY_GUILD_ID_CACHE.equals(key)) {
                builder.guildId(asString(value));
            } else if (LEGACY_KEY_GUILD_ID.equals(key) && !raw.containsKey(KEY_GUILD_ID_CACHE)) {
                // Migration, one way. Guarded so a file carrying both — a downgrade and re-upgrade —
                // takes the current key rather than whichever the map iterated last.
                builder.guildId(asString(value));
            }
        }
        return builder.build();
    }

    /**
     * Writes the config, preserving any keys in the file this version does not understand.
     *
     * <p>The unknown keys are re-read from disk <strong>here</strong>, not remembered from an
     * earlier {@link #load()}. That is the difference between a store you have to use in the right
     * order and one you cannot get wrong: a caller that saves without having loaded first — a
     * setup flow writing a fresh config, a second {@code BootstrapStore} over the same path — would
     * otherwise silently delete a newer version's settings, and the tell would be a config file
     * that quietly lost a field on downgrade.
     *
     * <p>Synchronized with {@link #load()}, so two threads cannot interleave a read-modify-write of
     * the same file.
     *
     * @throws IOException if the file could not be read back or written
     */
    public synchronized void save(BootstrapConfig config) throws IOException {
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
        // Written unconditionally rather than only-when-non-default. A file that omits them reads
        // the defaults back (load() fills any missing key), but writing them makes bootstrap.yml a
        // complete statement of the login budget and the update cadence — which is what an operator
        // migrating from v2 needs to be able to see and edit, since these are the one place v3 can
        // actually control them (see the BootstrapConfig class note).
        document.put(KEY_TIMEOUT_MS, Integer.valueOf(config.timeoutMs()));
        document.put(KEY_RETRIES, Integer.valueOf(config.retries()));
        document.put(KEY_RETRY_DELAY_MS, Integer.valueOf(config.retryDelayMs()));
        document.put(KEY_UPDATES_CHECK, Boolean.valueOf(config.updatesCheckEnabled()));
        document.put(KEY_UPDATES_NOTIFY, Boolean.valueOf(config.updatesNotifyAdmins()));
        document.put(KEY_UPDATES_INTERVAL, Long.valueOf(config.updatesCheckIntervalHours()));
        document.put(KEY_DISABLED_MODULES, config.disabledModules());
        // Written last of the known keys so it reads as what it is: an appendix the plugin
        // maintains, below the fields the operator was actually asked for. The legacy spelling is
        // deliberately not written back, so a file rewritten by this version ends up with one key
        // rather than two saying the same thing.
        document.put(KEY_GUILD_ID_CACHE, config.guildId());
        document.putAll(unknownKeys());

        AtomicFiles.writeUtf8(file, dumper().dump(document));
        logger.debug(() -> "Wrote " + file + " (" + config + ")");
    }

    /**
     * The keys currently in the file that this version does not interpret.
     *
     * <p>Read fresh on every save. A file that cannot be read or parsed yields none — the same
     * tolerance {@link #load()} applies, since refusing to save because the <em>old</em> file was
     * corrupt would leave the server unable to complete setup.
     */
    private Map<String, Object> unknownKeys() {
        if (!exists()) {
            return Collections.emptyMap();
        }
        Map<String, Object> raw;
        try {
            raw = readYaml();
        } catch (IOException e) {
            logger.warn("Could not re-read " + file + " before saving; any unknown keys in it will "
                    + "be lost: " + e.getMessage());
            return Collections.emptyMap();
        } catch (RuntimeException e) {
            logger.warn("Could not parse " + file + " before saving; any unknown keys in it will "
                    + "be lost: " + e.getMessage());
            return Collections.emptyMap();
        }

        Map<String, Object> unknown = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!KNOWN_KEYS.contains(entry.getKey())) {
                unknown.put(entry.getKey(), entry.getValue());
            }
        }
        if (!unknown.isEmpty()) {
            logger.debug("Preserving " + unknown.size() + " key(s) in " + file
                    + " that this version does not use");
        }
        return unknown;
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

    /**
     * A boolean whose <em>default is a parameter</em>, for a key whose sensible default is true.
     *
     * <p>{@link #asBoolean} answers {@code false} for a missing or unrecognised value, which is right
     * for a field that defaults off (debug) and wrong for one that defaults on (the update check). A
     * hand-edited {@code updatesCheckEnabled: maybe} must not silently disable update checks.
     */
    private static boolean asBooleanOr(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text) || "no".equalsIgnoreCase(text) || "off".equalsIgnoreCase(text)) {
            return false;
        }
        return fallback;
    }

    /**
     * A whole number, tolerating the quoted form a hand-edited YAML file often carries.
     *
     * <p>An unparseable value yields the default rather than throwing: a typo in a tuning knob must
     * not stop a server booting far enough to say what is wrong with its config.
     */
    private static long asLong(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
