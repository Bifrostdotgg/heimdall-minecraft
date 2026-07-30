package com.heimdall.platform.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.LogLine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The JUL capture path, driven directly — and, once, driven for real.
 *
 * <p>Most of these call {@link JulConsoleTap#capture(LogRecord)} with real {@link LogRecord}s, which
 * is the same method the handler calls with the same argument. What is JUL-specific lives there: the
 * level floor (whose comparison runs the opposite way from log4j's), the level-name mapping and the
 * parameter substitution. Everything below that — the ANSI strip, the queue bound, the drain — is
 * {@link ConsoleTap}'s and is already covered by {@link Log4jConsoleTapTest}.
 *
 * <p>{@link Attaching} is the exception and the important one: it runs the real
 * {@link ConsoleTap#attach()} against a real {@link Logger}, so the self-test probe has to travel
 * handler → filter → strip → queue → executor → consumer for the test to pass. That is the whole
 * mechanism the smoke matrix's {@code console tap on} assertion stands on, proven here without a
 * proxy. The logger is a private one with {@code useParentHandlers} off, so nothing this test does
 * reaches the JVM's root logger or a build log.
 */
class JulConsoleTapTest {

    /** The escape character, as a value rather than a literal — see {@code Log4jConsoleTapTest}. */
    private static final String ESC = String.valueOf((char) 27);

    /** Distinct logger names, so two tests cannot share one {@code LogManager} entry. */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    /** Runs drains on the calling thread, so a capture is fully delivered when it returns. */
    private static final Executor INLINE = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private final RecordingLogger logger = new RecordingLogger();

    private JulConsoleTap tap;
    private Logger target;

    @AfterEach
    void tearDown() {
        if (tap != null) {
            tap.close();
        }
    }

    /**
     * A logger nothing else can reach, standing in for the proxy's own.
     *
     * <p>{@code setUseParentHandlers(false)} mirrors {@code BungeeLogger}, which does exactly that —
     * and it is also what stops a probe line from this test appearing in the build output.
     */
    private JulConsoleTap tapOn(Executor executor) {
        target = Logger.getLogger("heimdall-console-tap-test-" + SEQUENCE.incrementAndGet());
        target.setUseParentHandlers(false);
        target.setLevel(Level.ALL);
        tap = new JulConsoleTap(logger, executor, target);
        return tap;
    }

    private JulConsoleTap inlineTap() {
        return tapOn(INLINE);
    }

    private static LogRecord record(Level level, String message) {
        return new LogRecord(level, message);
    }

    /** Collects everything the tap delivers. */
    private static final class Collector implements Consumer<LogLine> {

        private final List<LogLine> lines = Collections.synchronizedList(new ArrayList<LogLine>());

        @Override
        public void accept(LogLine line) {
            lines.add(line);
        }

        List<LogLine> lines() {
            synchronized (lines) {
                return new ArrayList<LogLine>(lines);
            }
        }

        List<String> messages() {
            List<String> messages = new ArrayList<String>();
            for (LogLine line : lines()) {
                messages.add(line.message());
            }
            return messages;
        }
    }

    @Nested
    @DisplayName("the level floor runs the other way from log4j's")
    class LevelFloor {

        @Test
        @DisplayName("INFO and everything more severe is captured")
        void severeEnoughIsCaptured() {
            Collector collector = new Collector();
            inlineTap().addTap(collector);

            tap.capture(record(Level.INFO, "info"));
            tap.capture(record(Level.WARNING, "warn"));
            tap.capture(record(Level.SEVERE, "severe"));

            assertEquals(Arrays.asList("info", "warn", "severe"), collector.messages());
        }

        @Test
        @DisplayName("CONFIG, FINE and below are dropped")
        void tooChattyIsDropped() {
            // The whole point of this test. JUL's intValue() rises with severity and log4j's falls,
            // so a floor copied across from the log4j tap without flipping the comparison inverts
            // the filter: every FINEST line ships and nothing else does. Both ends are pinned.
            Collector collector = new Collector();
            inlineTap().addTap(collector);

            tap.capture(record(Level.CONFIG, "config"));
            tap.capture(record(Level.FINE, "fine"));
            tap.capture(record(Level.FINEST, "finest"));

            assertTrue(collector.lines().isEmpty(),
                    "shipping every FINE line over a WebSocket is a different feature: "
                            + collector.messages());
        }

        @Test
        @DisplayName("a record with no level at all is dropped rather than thrown on")
        void nullLevelIsDropped() {
            Collector collector = new Collector();
            inlineTap().addTap(collector);

            // Subclassed rather than set: LogRecord.setLevel(null) throws outright, so the only way
            // a null level reaches a handler is a record that was not built by that setter — a
            // deserialised one, or another logging facade's adapter. The guard is cheap and the
            // alternative is an NPE on the proxy's logging path.
            LogRecord noLevel = new LogRecord(Level.INFO, "no level") {
                @Override
                public Level getLevel() {
                    return null;
                }
            };

            assertDoesNotThrow(() -> tap.capture(noLevel));
            assertTrue(collector.lines().isEmpty());
        }
    }

    @Nested
    @DisplayName("level names are the ones every other platform sends")
    class LevelNames {

        @Test
        @DisplayName("WARNING and SEVERE become WARN and ERROR")
        void julNamesAreMapped() {
            Collector collector = new Collector();
            inlineTap().addTap(collector);

            tap.capture(record(Level.WARNING, "careful"));
            tap.capture(record(Level.SEVERE, "broken"));

            assertEquals("WARN", collector.lines().get(0).level());
            assertEquals("ERROR", collector.lines().get(1).level(),
                    "a proxy sending SEVERE while every backend behind it sends ERROR is one "
                            + "feature displaying two vocabularies");
        }

        @Test
        @DisplayName("INFO and anything unrecognised pass through as the backend spelled them")
        void otherNamesPassThrough() {
            Collector collector = new Collector();
            inlineTap().addTap(collector);

            tap.capture(record(Level.INFO, "ordinary"));
            tap.capture(record(new Level("AUDIT", Level.INFO.intValue() + 1) {
            }, "invented"));

            assertEquals("INFO", collector.lines().get(0).level());
            assertEquals("AUDIT", collector.lines().get(1).level());
        }
    }

    @Nested
    @DisplayName("parameter substitution")
    class Parameters {

        @Test
        @DisplayName("a parameterised message is rendered, not shipped with its placeholders")
        void placeholdersAreSubstituted() {
            // BungeeCord's own shutdown path logs exactly this shape:
            // getLogger().log(Level.INFO, "Disconnecting {0} connections", connections.size()).
            Collector collector = new Collector();
            inlineTap().addTap(collector);

            LogRecord parameterised = record(Level.INFO, "Disconnecting {0} connections");
            parameterised.setParameters(new Object[] {7});

            tap.capture(parameterised);

            assertEquals(Collections.singletonList("Disconnecting 7 connections"),
                    collector.messages());
        }

        @Test
        @DisplayName("a message with no placeholders is left exactly alone")
        void unparameterisedTextIsUntouched() {
            // MessageFormat treats a single quote as an escape, so running an ordinary console line
            // through it would silently eat apostrophes: "Can't bind" becomes "Cant bind". The
            // placeholder probe is what stops that, and this is the line that would prove it gone.
            Collector collector = new Collector();
            inlineTap().addTap(collector);

            LogRecord noPlaceholders = record(Level.INFO, "Can't bind to that address");
            noPlaceholders.setParameters(new Object[] {"ignored"});

            tap.capture(noPlaceholders);

            assertEquals(Collections.singletonList("Can't bind to that address"),
                    collector.messages());
        }

        @Test
        @DisplayName("a message that only looks like a pattern falls back to itself")
        void brokenPatternIsContained() {
            Collector collector = new Collector();
            inlineTap().addTap(collector);

            // An unmatched brace: MessageFormat refuses to parse it at all and throws
            // IllegalArgumentException. That would otherwise escape into the proxy's logging thread
            // for a console line somebody's plugin happened to write with a stray '{'.
            LogRecord hostile = record(Level.INFO, "unclosed {0 brace");
            hostile.setParameters(new Object[] {1});

            assertDoesNotThrow(() -> tap.capture(hostile));
            assertEquals(Collections.singletonList("unclosed {0 brace"), collector.messages());
        }

        @Test
        @DisplayName("a null message is captured as empty rather than throwing")
        void nullMessageIsEmpty() {
            Collector collector = new Collector();
            inlineTap().addTap(collector);

            tap.capture(record(Level.INFO, null));

            assertEquals(Collections.singletonList(""), collector.messages());
        }
    }

    @Test
    @DisplayName("ANSI is stripped here too — the proxy console is coloured")
    void ansiIsStripped() {
        // BungeeCord's ColouredWriter emits real escape sequences, and a raw ESC reaching the
        // dashboard renders as mojibake. The pattern is ConsoleTap's, shared; this asserts that the
        // JUL side actually goes through it rather than around it.
        Collector collector = new Collector();
        inlineTap().addTap(collector);

        tap.capture(record(Level.INFO, ESC + "[33m[Heimdall]" + ESC + "[0m enabled"));

        assertEquals("[Heimdall] enabled", collector.messages().get(0));
    }

    @Nested
    @DisplayName("attaching for real")
    class Attaching {

        @Test
        @DisplayName("attach proves the whole path, not just that a handler was added")
        void attachSelfTestPasses() {
            Collector collector = new Collector();
            inlineTap();

            assertTrue(tap.attach(),
                    "attach() logs a probe line through the target logger and waits to receive it "
                            + "back through handler, filter, strip, queue, executor and consumer — a "
                            + "false here means one of those six is broken: " + logger.records());
            assertTrue(tap.isAttached());

            // And the handler really is live afterwards: a line logged on the target arrives.
            tap.addTap(collector);
            target.info("after attach");
            assertEquals(Collections.singletonList("after attach"), collector.messages());
        }

        @Test
        @DisplayName("two attaches in one JVM use different probe markers")
        void probeMarkersAreDistinctPerAttach() {
            // The reload case the nonce exists for, and the one it did not cover: the marker used to
            // be read rather than incremented, so every attach in a process used the same string —
            // and a probe line a previous instance left in a queue could then satisfy the next
            // attach on a capture path that no longer worked.
            Collector first = new Collector();
            inlineTap();
            tap.addTap(first);
            assertTrue(tap.attach());

            // close() clears the taps as well as detaching, so a fresh collector is needed.
            tap.close();
            Collector second = new Collector();
            tap.addTap(second);
            assertTrue(tap.attach());

            assertFalse(markerFrom(first).equals(markerFrom(second)),
                    "both attaches used " + markerFrom(first) + ", so the second proved nothing a "
                            + "leftover queue entry could not have proved for it");
        }

        /** The probe line the tap logged through the target during one attach. */
        private String markerFrom(Collector collector) {
            for (String message : collector.messages()) {
                if (message.startsWith("console tap self-test ")) {
                    return message;
                }
            }
            throw new AssertionError("no probe line was captured: " + collector.messages());
        }

        @Test
        @DisplayName("a plugin logger's lines arrive too — that is the whole reason for this attach point")
        void childLoggerLinesArrive() {
            // The finding this class is built on: BungeeCord's proxy logger has no parent and does
            // not use parent handlers, so the JUL ROOT sees nothing — but every PluginLogger sets
            // the proxy logger as ITS parent. So a handler here captures every plugin's output.
            // This reproduces that shape exactly: a child whose parent is the target.
            Collector collector = new Collector();
            inlineTap();
            assertTrue(tap.attach());
            tap.addTap(collector);

            Logger child = Logger.getLogger(target.getName() + ".plugin");
            child.setParent(target);
            child.info("[SomePlugin] hello");

            assertEquals(Collections.singletonList("[SomePlugin] hello"), collector.messages());
        }

        @Test
        @DisplayName("close removes the handler, so a reload cannot stack them")
        void closeRemovesTheHandler() {
            inlineTap();
            assertTrue(tap.attach());
            int attachedCount = target.getHandlers().length;

            tap.close();

            assertFalse(tap.isAttached());
            assertEquals(attachedCount - 1, target.getHandlers().length,
                    "a handler left on the logger is one leaked per plugin reload");
        }

        @Test
        @DisplayName("a logger that delivers to nobody is reported as attached-but-deaf, and detached")
        void deafLoggerIsDetached() {
            // The case the self-test exists for: the handler attaches perfectly and no record ever
            // reaches it. Reproduced by a logger whose level refuses everything, which is what a
            // misconfigured or wrapped logging backend looks like from here.
            target = Logger.getLogger("heimdall-console-tap-deaf-" + SEQUENCE.incrementAndGet());
            target.setUseParentHandlers(false);
            target.setLevel(Level.OFF);
            tap = new JulConsoleTap(logger, INLINE, target);

            assertFalse(tap.attach(),
                    "a tap that captured nothing must not report success — the banner the smoke "
                            + "matrix asserts on would then be a lie");
            assertFalse(tap.isAttached());
            assertEquals(0, target.getHandlers().length,
                    "a dead handler left on the logger is worse than none");
            assertTrue(logger.logged(com.heimdall.core.log.LogLevel.WARN, "did not capture"),
                    "the operator has to be told which half failed: " + logger.records());
        }
    }
}
