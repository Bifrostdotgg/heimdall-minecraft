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

    String etag;
    Map<String, MirrorEntry<T>> entries;

    MirrorSnapshot() {
        this(null, new LinkedHashMap<String, MirrorEntry<T>>());
    }

    MirrorSnapshot(String etag, Map<String, MirrorEntry<T>> entries) {
        this.etag = etag;
        this.entries = entries;
    }
}
