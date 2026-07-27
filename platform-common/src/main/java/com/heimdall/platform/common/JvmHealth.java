package com.heimdall.platform.common;

import com.heimdall.core.json.Payload;

/**
 * The half of the health snapshot that is the same everywhere.
 *
 * <p>TPS, MSPT and the player counts differ per platform — a proxy has no tick loop at all — but
 * heap usage is a JVM question, so it is answered once here rather than in two files that would
 * eventually disagree about whether a megabyte is 1000 or 1024 kilobytes.
 *
 * <p>Megabytes, matching what the dashboard renders and what v2 sent.
 */
public final class JvmHealth {

    private static final long BYTES_PER_MB = 1024L * 1024L;

    private JvmHealth() {
    }

    /** Adds {@code usedMemMb} and {@code maxMemMb} to a snapshot under construction. */
    public static Payload.Builder memory(Payload.Builder builder) {
        Runtime runtime = Runtime.getRuntime();
        return builder
                .put("usedMemMb", (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB)
                .put("maxMemMb", runtime.maxMemory() / BYTES_PER_MB);
    }
}
