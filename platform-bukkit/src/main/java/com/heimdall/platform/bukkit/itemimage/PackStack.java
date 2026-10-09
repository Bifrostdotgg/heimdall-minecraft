package com.heimdall.platform.bukkit.itemimage;

import com.heimdall.core.items.Snbt;
import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every asset source, highest priority first: what the client would see with the server's packs
 * applied over vanilla. A path resolves to the first source that has it, exactly as pack stacking
 * works in the client.
 *
 * <p>Built and used on the render thread only, and replaced (and closed) whole when any source
 * changes, so there is no locking here.
 */
final class PackStack implements Closeable {

    /** Largest model, item definition, mcmeta or font JSON read. */
    static final int MAX_JSON_BYTES = 1024 * 1024;

    /** Largest language file read. */
    static final int MAX_LANG_BYTES = 8 * 1024 * 1024;

    private final List<AssetRoot> roots;
    private final String fingerprint;

    PackStack(List<AssetRoot> roots, String fingerprint) {
        this.roots = Collections.unmodifiableList(new ArrayList<AssetRoot>(roots));
        this.fingerprint = fingerprint;
    }

    /** What the stack was built from; part of every render-cache key. */
    String fingerprint() {
        return fingerprint;
    }

    int size() {
        return roots.size();
    }

    /** The first source's bytes for {@code path}, or {@code null}. */
    byte[] read(String path, int maxBytes) {
        for (AssetRoot root : roots) {
            byte[] found = root.read(path, maxBytes);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** {@code path} parsed as JSON, or {@code null} if missing or malformed. */
    Object json(String path) {
        byte[] bytes = read(path, MAX_JSON_BYTES);
        if (bytes == null) {
            return null;
        }
        try {
            return Snbt.parse(AssetRoot.stripBom(new String(bytes, StandardCharsets.UTF_8)));
        } catch (Snbt.SyntaxException malformed) {
            return null;
        }
    }

    /**
     * English names from every source, a higher-priority source overriding a lower one: the modern
     * {@code en_us.json} and the pre-1.13 {@code en_us.lang} / {@code en_US.lang} key=value form.
     * Only the {@code minecraft} namespace and the namespaces packs commonly add names under are
     * read, so this never walks a pack.
     */
    Map<String, String> english(List<String> namespaces) {
        Map<String, String> out = new HashMap<String, String>();
        for (int i = roots.size() - 1; i >= 0; i--) {
            AssetRoot root = roots.get(i);
            for (String namespace : namespaces) {
                String base = "assets/" + namespace + "/lang/";
                byte[] json = root.read(base + "en_us.json", MAX_LANG_BYTES);
                if (json != null) {
                    try {
                        Map<String, Object> map = Snbt.asMap(Snbt.parse(AssetRoot.stripBom(
                                new String(json, StandardCharsets.UTF_8)), MAX_LANG_BYTES));
                        if (map != null) {
                            for (Map.Entry<String, Object> entry : map.entrySet()) {
                                if (entry.getValue() instanceof String) {
                                    out.put(entry.getKey(), (String) entry.getValue());
                                }
                            }
                        }
                    } catch (Snbt.SyntaxException malformed) {
                        // A broken language file costs its names, nothing else.
                    }
                    continue;
                }
                byte[] legacy = root.read(base + "en_us.lang", MAX_LANG_BYTES);
                if (legacy == null) {
                    legacy = root.read(base + "en_US.lang", MAX_LANG_BYTES);
                }
                if (legacy != null) {
                    for (String line : new String(legacy, StandardCharsets.UTF_8).split("\n")) {
                        int eq = line.indexOf('=');
                        if (eq > 0 && !line.startsWith("#")) {
                            out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                        }
                    }
                }
            }
        }
        return out;
    }

    @Override
    public void close() {
        for (AssetRoot root : roots) {
            root.close();
        }
    }

    /** Sources, not contents. */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("PackStack[");
        for (AssetRoot root : roots) {
            out.append(root.describe()).append("; ");
        }
        return out.append(']').toString();
    }
}
