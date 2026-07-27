package com.heimdall.stubbot;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * One-line structured logging to stdout.
 *
 * <p>Deliberately not slf4j: the smoke harness greps this output, so the format is part of what the
 * fixture guarantees, not something a logging config on the runner can reshape.
 */
public final class StubLog {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private static volatile boolean verbose =
            Boolean.parseBoolean(System.getenv().getOrDefault("STUB_BOT_VERBOSE", "false"));

    private StubLog() {
    }

    public static void setVerbose(boolean value) {
        verbose = value;
    }

    public static void info(String message) {
        System.out.println("[stub-bot " + TS.format(Instant.now()) + "] " + message);
    }

    public static void warn(String message) {
        System.out.println("[stub-bot " + TS.format(Instant.now()) + "] WARN " + message);
    }

    public static void debug(String message) {
        if (verbose) {
            System.out.println("[stub-bot " + TS.format(Instant.now()) + "] DEBUG " + message);
        }
    }
}
