package com.heimdall.core.migrate;

import com.heimdall.core.util.Strings;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed v2 {@code config.yml} or {@code config.json}, read through one set of typed accessors.
 *
 * <h2>Why one type for two file formats</h2>
 *
 * <p>v2 shipped the same ~200 settings twice: as YAML on Bukkit and as JSON on Velocity, with two
 * separate {@code ConfigProvider} implementations and two separate sets of defaults written out by
 * hand. The two drifted — the Velocity default document has no {@code roleSync}, no {@code console}
 * and no {@code updates} block at all, so a Velocity operator who never edited their file was
 * running settings their config did not mention. A migration that read the two formats through two
 * code paths would inherit that drift and quietly migrate a Velocity network differently from an
 * identical Bukkit server.
 *
 * <p>So {@link V2ConfigReader} flattens both formats into the same nested {@code Map} and this class
 * is the only thing that decides what a key <em>means</em>. Every default below is v2's own shipped
 * value, taken from {@code src/main/resources/config.yml} on the {@code v2-maintenance} branch, and
 * it is applied identically whichever file the value came out of.
 *
 * <h2>Tolerant on purpose</h2>
 *
 * <p>No accessor throws, for any input. A v2 config has been sitting in a plugins directory being
 * hand-edited for a year or more: keys are missing, keys are the wrong type, numbers are quoted
 * because somebody's editor quoted them, and {@code enabled: "true"} is a real thing people write.
 * Every one of those has to yield the v2 default rather than an exception, because the failure this
 * whole package prevents is a server that will not boot far enough to tell its operator what is
 * wrong.
 *
 * <p>{@link #hasCredentials()} is the one judgement call this type makes, and it is the gate the
 * migration turns on — see its javadoc.
 *
 * <p><strong>Immutable.</strong> The tree handed to {@link #of(Map)} is deep-copied into
 * unmodifiable maps and lists at that boundary, so no caller retains a handle on what the accessors
 * read. Scalar leaves are shared by reference; SnakeYAML and Gson produce Strings, Booleans and
 * Numbers for everything this class reads.
 */
public final class V2Config {

    /**
     * The API key v2 shipped in its default config, which means "nobody has configured this yet".
     *
     * <p>It matters because it is <em>present and non-blank</em>. A blank-check alone would treat a
     * pristine, never-edited v2 config as a configured server, write a {@code bootstrap.yml} whose
     * token is the literal string {@code your-api-key-here}, and rename the operator's file out from
     * under them — leaving a server that authenticates against nothing and an operator who now has a
     * v3 config claiming credentials it does not have.
     */
    public static final String PLACEHOLDER_API_KEY = "your-api-key-here";

    private final Map<String, Object> root;

    private V2Config(Map<String, Object> root) {
        this.root = root;
    }

    /**
     * Adopts a parsed document, copying it at the boundary.
     *
     * <p>{@code null}, or anything that is not a mapping, yields a config on which every accessor
     * answers its v2 default. That is deliberate rather than an error: a v2 file that parses to a
     * bare scalar has no settings in it, which is exactly the same amount of information as an empty
     * one, and {@link #hasCredentials()} will refuse it a moment later anyway.
     */
    public static V2Config of(Map<String, Object> document) {
        if (document == null) {
            return new V2Config(Collections.<String, Object>emptyMap());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> copied = (Map<String, Object>) copy(document);
        return new V2Config(copied);
    }

    // ── Credentials and identity: what becomes bootstrap.yml ─────────────────

    /** v2 {@code api.baseUrl} — the bot's base URL. */
    public String baseUrl() {
        return string("api.baseUrl", "");
    }

    /** v2 {@code api.apiKey} — the guild API key, which becomes v3's legacy token. Never log this. */
    public String apiKey() {
        return string("api.apiKey", "");
    }

    /**
     * v2 {@code api.guildId} — the hand-typed guild snowflake departure D54 abolished.
     *
     * <p>Carried over only as the {@code guildIdCache} seed, never as a setting. The token is what
     * actually knows the answer, and {@code identify} overwrites whatever this said on the first
     * boot that reaches the bot.
     */
    public String guildId() {
        return string("api.guildId", "");
    }

    /** v2 {@code server.serverId} — this server's own id within the guild. Often blank in v2. */
    public String serverId() {
        return string("server.serverId", "");
    }

    /** v2 {@code logging.debug} — the one diagnostic knob that stays local in v3. */
    public boolean debug() {
        return bool("logging.debug", false);
    }

    // ── Everything else: what becomes the dashboard's config document ────────

    /**
     * v2's top-level {@code enabled} kill switch, default {@code false}.
     *
     * <p>v2 shipped disabled on purpose — while off, every player joined without a whitelist check —
     * so this is emphatically not a "did the operator configure it" signal, and it is not treated as
     * one. It maps to the {@code whitelist} module's {@code enabled}, which is the same switch under
     * a new name.
     */
    public boolean pluginEnabled() {
        return bool("enabled", false);
    }

    /** v2 {@code cache.cacheWindow}, in minutes. */
    public long cacheWindowMinutes() {
        return longValue("cache.cacheWindow", 60L);
    }

    /** v2 {@code cache.extendOnJoin}, in minutes. */
    public long extendOnJoinMinutes() {
        return longValue("cache.extendOnJoin", 120L);
    }

    /** v2 {@code cache.extendOnLeave}, in minutes. */
    public long extendOnLeaveMinutes() {
        return longValue("cache.extendOnLeave", 180L);
    }

    /** v2 {@code cache.maxExtensionHours} — the #771 revocation ceiling. {@code 0} disables it. */
    public long maxExtensionHours() {
        return longValue("cache.maxExtensionHours", 24L);
    }

    /** v2 {@code cache.cleanupInterval}, in minutes. Renamed to {@code cleanupIntervalMinutes} in v3. */
    public long cleanupIntervalMinutes() {
        return longValue("cache.cleanupInterval", 30L);
    }

    /** v2 {@code cache.prewarm.enabled} — flattened to {@code prewarmEnabled} in v3. */
    public boolean prewarmEnabled() {
        return bool("cache.prewarm.enabled", true);
    }

    /** v2 {@code cache.prewarm.intervalMinutes} — flattened to {@code prewarmIntervalMinutes} in v3. */
    public long prewarmIntervalMinutes() {
        return longValue("cache.prewarm.intervalMinutes", 5L);
    }

    /** v2 {@code advanced.apiFallbackMode}: {@code allow}, {@code whitelist-only} or {@code deny}. */
    public String apiFallbackMode() {
        return string("advanced.apiFallbackMode", "whitelist-only");
    }

    /**
     * v2 {@code bypass.uuids} — the UUIDs that skip the whitelist check entirely.
     *
     * <p>Unmodifiable, and never {@code null}. Non-scalar entries are dropped rather than failing the
     * list: a stray nested mapping in a hand-edited file should cost that entry, not the migration.
     */
    public List<String> bypassUuids() {
        Object value = valueAt("bypass.uuids");
        if (!(value instanceof Collection)) {
            return Collections.emptyList();
        }
        Collection<?> raw = (Collection<?>) value;
        List<String> out = new ArrayList<String>(raw.size());
        for (Object element : raw) {
            if (isScalar(element)) {
                out.add(String.valueOf(element));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** v2 {@code messages.apiUnavailable}, §-coded as v2 wrote it. */
    public String apiUnavailableMessage() {
        return string("messages.apiUnavailable",
                "§cWhitelist system is temporarily unavailable. Please try again later.");
    }

    /** v2 {@code messages.apiUnavailableAllowed}, §-coded as v2 wrote it. */
    public String apiUnavailableAllowedMessage() {
        return string("messages.apiUnavailableAllowed",
                "§eWhitelist API is temporarily unavailable. You have been allowed in from cache.");
    }

    /** v2 {@code roleSync.enabled}. Absent from the Velocity default document; {@code false} either way. */
    public boolean roleSyncEnabled() {
        return bool("roleSync.enabled", false);
    }

    /**
     * v2 {@code console.stream}, default {@code true}.
     *
     * <p>The Velocity default document has no {@code console} block at all, so this default is what
     * every un-edited Velocity install was actually running — the code default, not a file value.
     */
    public boolean consoleStream() {
        return bool("console.stream", true);
    }

    /** v2 {@code updates.checkEnabled}. Also absent from the Velocity default document. */
    public boolean updateCheckEnabled() {
        return bool("updates.checkEnabled", true);
    }

    /** v2 {@code updates.notifyAdmins}. */
    public boolean updateNotifyAdmins() {
        return bool("updates.notifyAdmins", true);
    }

    /** v2 {@code updates.checkIntervalHours}. v2 documented a minimum of 1; it is not clamped here. */
    public long updateCheckIntervalHours() {
        return longValue("updates.checkIntervalHours", 12L);
    }

    // ── The one judgement ────────────────────────────────────────────────────

    /**
     * Whether this file describes a server somebody actually set up.
     *
     * <p>An endpoint and an API key that is neither blank nor {@link #PLACEHOLDER_API_KEY}. A config
     * left at v2's shipped defaults is a file that exists, not a configured server, and it must not
     * produce a {@code bootstrap.yml} claiming to be one — see the constant's javadoc for what that
     * would leave behind.
     *
     * <p>Deliberately says nothing about {@link #serverId()} or {@link #guildId()}: both are blank in
     * v2's default file and both are things v3 can obtain for itself, so requiring them would refuse
     * to migrate configs that are perfectly usable.
     */
    public boolean hasCredentials() {
        String key = apiKey().trim();
        return Strings.isNotBlank(baseUrl())
                && Strings.isNotBlank(key)
                && !PLACEHOLDER_API_KEY.equalsIgnoreCase(key);
    }

    @Override
    public String toString() {
        return "V2Config{baseUrl='" + baseUrl()
                + "', apiKey=" + (Strings.isBlank(apiKey()) ? "<unset>" : "<redacted>")
                + ", guildId='" + guildId()
                + "', serverId='" + serverId()
                + "', keys=" + root.keySet()
                + "}";
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    /** Walks a dotted path. {@code null} for anything the path does not reach. */
    private Object valueAt(String path) {
        Object current = root;
        int start = 0;
        while (start <= path.length()) {
            int dot = path.indexOf('.', start);
            String segment = dot < 0 ? path.substring(start) : path.substring(start, dot);
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<?, ?>) current).get(segment);
            if (dot < 0) {
                return current;
            }
            start = dot + 1;
        }
        return current;
    }

    /** A scalar rendered as text, or {@code fallback} when the path is absent or holds a container. */
    private String string(String path, String fallback) {
        Object value = valueAt(path);
        return isScalar(value) ? String.valueOf(value) : fallback;
    }

    /**
     * A whole number, tolerating the quoted form.
     *
     * <p>{@code cacheWindow: "60"} is a real thing in hand-edited configs — some editors quote every
     * scalar — and v2's own {@code getLong} coerced it, because Gson's {@code getAsLong} parses a
     * string primitive. Reading it as absent would silently reset an operator's tuning to the default
     * during the one operation they cannot check afterwards.
     */
    private long longValue(String path, long fallback) {
        Object value = valueAt(path);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (!isScalar(value)) {
            return fallback;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim()).longValue();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * A boolean, tolerating the quoted form and answering the <em>default</em> for anything else.
     *
     * <p>Same tolerance as {@code BootstrapStore.asBoolean}, with one difference that matters here:
     * an unrecognised value falls back rather than becoming {@code false}. Three of these defaults
     * are {@code true} — pre-warm, console streaming, update checks — so coercing a typo to
     * {@code false} would turn a feature off during a migration, which is the one moment an operator
     * is least likely to notice.
     */
    private boolean bool(String path, boolean fallback) {
        Object value = valueAt(path);
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (!isScalar(value)) {
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

    private static boolean isScalar(Object value) {
        return value != null && !(value instanceof Map) && !(value instanceof Collection);
    }

    /** Deep-copies containers into unmodifiable ones; scalar leaves are shared. */
    private static Object copy(Object value) {
        if (value instanceof Map) {
            Map<?, ?> source = (Map<?, ?>) value;
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(String.valueOf(entry.getKey()), copy(entry.getValue()));
                }
            }
            return Collections.unmodifiableMap(out);
        }
        if (value instanceof Collection) {
            Collection<?> source = (Collection<?>) value;
            List<Object> out = new ArrayList<Object>(source.size());
            for (Object element : source) {
                out.add(copy(element));
            }
            return Collections.unmodifiableList(out);
        }
        return value;
    }
}
