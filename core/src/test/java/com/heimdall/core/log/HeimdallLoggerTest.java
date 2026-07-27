package com.heimdall.core.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The logging contract, in particular the two things v2 got wrong.
 *
 * <p>v2 asked its config provider whether debug was on for every single debug call, and built the
 * message string first regardless. Both are load-bearing on the login path, where a handful of
 * debug lines per join turned into dozens of config reads.
 */
class HeimdallLoggerTest {

    @Test
    @DisplayName("debug messages are dropped while the toggle is off")
    void debugIsGated() {
        RecordingLogger logger = new RecordingLogger();

        logger.debug("invisible");
        assertTrue(logger.at(LogLevel.DEBUG).isEmpty());

        logger.setDebugEnabled(true);
        logger.debug("visible");
        assertEquals(1, logger.at(LogLevel.DEBUG).size());
        assertEquals("visible", logger.at(LogLevel.DEBUG).get(0).message);
    }

    @Test
    @DisplayName("the supplier overload is not invoked while debug is off")
    void supplierIsNotInvokedWhenDisabled() {
        RecordingLogger logger = new RecordingLogger();
        AtomicInteger invocations = new AtomicInteger();

        logger.debug(() -> {
            invocations.incrementAndGet();
            return "expensive";
        });
        assertEquals(0, invocations.get(), "the whole point is that the string is never built");

        logger.setDebugEnabled(true);
        logger.debug(() -> {
            invocations.incrementAndGet();
            return "expensive";
        });
        assertEquals(1, invocations.get());
        assertEquals("expensive", logger.at(LogLevel.DEBUG).get(0).message);
    }

    @Test
    @DisplayName("a throwing debug supplier warns instead of propagating")
    void brokenSupplierDoesNotEscape() {
        RecordingLogger logger = new RecordingLogger(true);

        logger.debug(() -> {
            throw new IllegalStateException("boom");
        });

        assertTrue(logger.at(LogLevel.DEBUG).isEmpty());
        assertEquals(1, logger.at(LogLevel.WARN).size(),
                "a broken debug message must not fail the login it was describing");
    }

    @Test
    void everyLevelReachesTheImplementation() {
        RecordingLogger logger = new RecordingLogger(true);

        logger.info("i");
        logger.warn("w");
        logger.severe("s");
        logger.debug("d");

        assertEquals(1, logger.at(LogLevel.INFO).size());
        assertEquals(1, logger.at(LogLevel.WARN).size());
        assertEquals(1, logger.at(LogLevel.SEVERE).size());
        assertEquals(1, logger.at(LogLevel.DEBUG).size());
    }

    @Test
    @DisplayName("error() carries the cause through rather than flattening it")
    void errorCarriesTheThrowable() {
        RecordingLogger logger = new RecordingLogger();
        RuntimeException cause = new RuntimeException("cause");

        logger.error("failed", cause);

        RecordingLogger.Record record = logger.at(LogLevel.SEVERE).get(0);
        assertEquals("failed", record.message);
        assertSame(cause, record.throwable);
    }

    @Test
    void theToggleIsReadable() {
        RecordingLogger logger = new RecordingLogger();
        assertFalse(logger.isDebugEnabled());
        logger.setDebugEnabled(true);
        assertTrue(logger.isDebugEnabled());
        logger.setDebugEnabled(false);
        assertFalse(logger.isDebugEnabled());
    }

    @Test
    @DisplayName("the JUL implementation accepts every level without throwing")
    void julImplementationWorks() {
        JulLogger logger = JulLogger.named("heimdall-test");
        logger.setDebugEnabled(true);

        logger.info("i");
        logger.warn("w");
        logger.severe("s");
        logger.debug("d");
        logger.debug(() -> "d2");
        logger.error("e", new RuntimeException("cause"));

        assertTrue(logger.isDebugEnabled());
    }
}
