package com.heimdall.core.mirror;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The on-disk document: the mirrored entries plus the ETag they arrived with.
 *
 * <p>Field names are the file format. Renaming one silently loses that field's value for every
 * already-deployed server on its next boot.
 *
 * <p>The ETag rides along with the entries deliberately: without it a restart has to pull a full
 * dump it already holds, which for a large network is the most expensive request the plugin makes
 * and the one most likely to coincide with a bot redeploy.
 *
 * @param <T> the mirrored value type
 */
final class MirrorSnapshot<T> {

    final String etag;

    /**
     * The mirrored entries.
     *
     * <p>Copied on the way in, so the map the store handed over cannot keep changing while the
     * serializer walks it. The entries themselves are already immutable, so this copy is the last
     * piece that makes a save a genuine point-in-time snapshot.
     *
     * <p>Nullable only because Gson leaves it so when the key is absent from the file; {@link
     * MirrorFile#load()} treats that as an empty snapshot.
     */
    final Map<String, MirrorEntry<T>> entries;

    MirrorSnapshot() {
        this(null, null);
    }

    MirrorSnapshot(String etag, Map<String, MirrorEntry<T>> entries) {
        this.etag = etag;
        this.entries = entries == null
                ? new LinkedHashMap<String, MirrorEntry<T>>()
                : new LinkedHashMap<String, MirrorEntry<T>>(entries);
    }
}
