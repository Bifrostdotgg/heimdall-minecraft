package com.heimdall.core.config;

import com.heimdall.core.log.HeimdallLogger;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads one flag out of somebody else's YAML file, and never fails doing it.
 *
 * <p>The platform modules need to answer questions about the <em>server's</em> configuration —
 * whether {@code spigot.yml} has BungeeCord forwarding on, whether Paper's velocity support is
 * enabled — and those files are not Heimdall's to own. This is the whole of what that requires: one
 * value, at one dotted path, from one file that may not exist.
 *
 * <h2>Why not the server's own config API</h2>
 *
 * <p>Bukkit ships {@code YamlConfiguration}, which does exactly this and is present on every
 * version. Using it would bind the answer to the server's bundled SnakeYAML, whose API changed
 * incompatibly at 2.0 — Bukkit's loader still initialises against a Base64 helper that no longer
 * exists there — so the choice would be between the version the server carries and the version
 * Heimdall shades, and only one of those is knowable at build time. Heimdall's own SnakeYAML is
 * relocated into {@code com.heimdall.libs.snakeyaml} and cannot be affected by what the server has.
 *
 * <p>Keeping the parser in core also keeps the relocation surface in one place: no platform module
 * imports {@code org.yaml.snakeyaml} itself, exactly as no module imports Gson.
 *
 * <h2>Every failure is the fallback</h2>
 *
 * <p>A missing file, an unreadable one, malformed YAML, a path that leads nowhere, a value that is
 * not a boolean — all of them return {@code fallback}. That is not laziness: this reads a file
 * another program owns and may be halfway through writing, and the caller's question ("is
 * forwarding on?") always has a safe answer.
 */
public final class YamlProbe {

    private YamlProbe() {
    }

    /**
     * The boolean at {@code dottedPath}, or {@code fallback}.
     *
     * @param file the YAML file; {@code null} or absent yields {@code fallback}
     * @param dottedPath a nested key path, e.g. {@code settings.bungeecord}
     * @param logger where a parse failure is noted at debug; may be {@code null}
     */
    public static boolean flag(
            Path file, String dottedPath, boolean fallback, HeimdallLogger logger) {
        Object value = value(file, dottedPath, logger);
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value == null) {
            return fallback;
        }
        // A hand-edited file can carry a quoted "true", which SnakeYAML hands back as a String and
        // which would otherwise silently mean false — the same coercion BootstrapStore applies.
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text)
                || "on".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text) || "no".equalsIgnoreCase(text)
                || "off".equalsIgnoreCase(text)) {
            return false;
        }
        return fallback;
    }

    /**
     * The raw value at {@code dottedPath}, or {@code null} if it is not there.
     *
     * <p>Exposed as well as {@link #flag} because a caller reading a string setting should not have
     * to re-implement the walk, and because "absent" and "present but not a boolean" are different
     * facts that {@link #flag} deliberately collapses.
     */
    public static Object value(Path file, String dottedPath, HeimdallLogger logger) {
        if (file == null || dottedPath == null || dottedPath.isEmpty()
                || !Files.isRegularFile(file)) {
            return null;
        }
        Object document;
        try (InputStream in = Files.newInputStream(file)) {
            // SafeConstructor, like everywhere else Heimdall parses YAML: these files sit in a
            // directory the operator's other plugins and FTP clients can write to, and SnakeYAML's
            // default constructor will instantiate any class a document names.
            document = new Yaml(new SafeConstructor(new LoaderOptions()))
                    .load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception unreadable) {
            if (logger != null) {
                logger.debug(() -> "could not read " + file + ": " + unreadable);
            }
            return null;
        }
        return walk(document, dottedPath.split("\\."));
    }

    @SuppressWarnings("unchecked")
    private static Object walk(Object node, String[] segments) {
        Object current = node;
        for (String segment : segments) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<Object, Object>) current).get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }
}
