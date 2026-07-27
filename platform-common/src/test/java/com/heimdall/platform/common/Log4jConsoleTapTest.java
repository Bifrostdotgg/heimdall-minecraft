package com.heimdall.platform.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.LogLine;
import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The capture path, driven directly.
 *
 * <p>{@code attach()} puts an appender on the JVM's root logger, which is a bad thing for a unit
 * test to do and a worse thing for one to leave behind — so these call {@link Log4jConsoleTap#capture}
 * with real {@link LogEvent}s instead. That is the same method the appender calls, with the same
 * arguments, and it is where every decision lives: the level floor, the ANSI strip, the queue
 * bound, the hand-off to the executor, and the three rules about never logging, never throwing and
 * never blocking.
 *
 * <p>Real {@code Log4jLogEvent}s rather than a hand-rolled fake, deliberately. The whole reason this
 * class is written the way it is (departure D45) is that Log4j's API shape differs across the
 * decade of versions Minecraft spans, and a fake {@code LogEvent} would only ever agree with the
 * test's own idea of that shape.
 *
 * <p>The executor is inline in most tests, which makes the fan-out synchronous and the assertions
 * ordinary. The one test that needs a real thread says so.
 */
class Log4jConsoleTapTest {

    private final RecordingLogger logger = new RecordingLogger();

    /**
     * The escape character, as a value rather than a literal.
     *
     * <p>Written this way on purpose: a raw ESC in a source file survives exactly as long as
     * nothing re-encodes the file, and a unicode escape is rewritten by javac's preprocessor
     * preprocessor before the string is even parsed. Neither is something a test about escape
     * sequences should depend on.
     */
    private static final String ESC = String.valueOf((char) 27);

    /** The bell, which is how jline terminates a title sequence. Same reasoning as {@link #ESC}. */
    private static final String BEL = String.valueOf((char) 7);

    /** Runs drains on the calling thread, so a capture is fully delivered when it returns. */
    private static final Executor INLINE = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private Log4jConsoleTap tap;
    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (tap != null) {
            tap.close();
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private Log4jConsoleTap inlineTap() {
        tap = new Log4jConsoleTap(logger, INLINE);
        return tap;
    }

    private static LogEvent event(Level level, String message) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName("test")
                .setLevel(level)
                .setMessage(new SimpleMessage(message))
                .build();
    }

    /**
     * A {@link Message} whose rendering is whatever the supplier does — including throwing.
     *
     * <p>Implemented rather than subclassed: {@code SimpleMessage} is a concrete class whose
     * shape has moved across Log4j versions, and this test has no business depending on which of
     * its methods happen to be overridable today.
     */
    private static Message messageThat(final java.util.function.Supplier<String> rendering) {
        return new Message() {

            @Override
            public String getFormattedMessage() {
                return rendering.get();
            }

            @Override
            public String getFormat() {
                return "";
            }

            @Override
            public Object[] getParameters() {
                return new Object[0];
            }

            @Override
            public Throwable getThrowable() {
                return null;
            }
        };
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
    @DisplayName("the level floor")
    class LevelFilter {

        @Test
        @DisplayName("INFO and everything more severe is captured")
        void severeEnoughIsCaptured() {
            Collector collector = new Collector();
            inlineTap().addTap(collector);

            tap.capture(event(Level.INFO, "info"));
            tap.capture(event(Level.WARN, "warn"));
            tap.capture(event(Level.ERROR, "error"));
            tap.capture(event(Level.FATAL, "fatal"));

            assertEquals(java.util.Arrays.asList("info", "warn", "error", "fatal"),
                    collector.messages());
        }

        @Test
        @DisplayName("DEBUG and TRACE are dropped — the volume is a debugging tool, not a feed")
        void tooChattyIsDropped() {
            Collector collector = new Collector();
            inlineTap().addTap(collector);

            tap.capture(event(Level.DEBUG, "debug"));
            tap.capture(event(Level.TRACE, "trace"));

            assertTrue(collector.lines().isEmpty(),
                    "shipping every DEBUG line over a WebSocket is a different feature: "
                            + collector.messages());
        }

        @Test
        @DisplayName("the level name reaches the consumer")
        void levelNameIsCarried() {
            Collector collector = new Collector();
            inlineTap().addTap(collector);

            tap.capture(event(Level.WARN, "careful"));

            assertEquals("WARN", collector.lines().get(0).level());
        }
    }

    @Nested
    @DisplayName("ANSI stripping")
    class Ansi {

        @Test
        @DisplayName("colour sequences are removed")
        void coloursAreStripped() {
            Collector collector = new Collector();
            inlineTap().addTap(collector);

            tap.capture(event(Level.INFO, ESC + "[33m[Heimdall]" + ESC + "[0m enabled"));

            assertEquals("[Heimdall] enabled", collector.messages().get(0));
        }

        @Test
        @DisplayName("ordinary brackets survive — v2's pattern ate them")
        void bracketsSurvive() {
            // v2's pattern began at the '[' rather than at the ESC, so a perfectly ordinary log
            // prefix lost its closing bracket on the way to Discord.
            Collector collector = new Collector();
            inlineTap().addTap(collector);

            tap.capture(event(Level.INFO, "[12:00:00 INFO]: [Heimdall] hello"));

            assertEquals("[12:00:00 INFO]: [Heimdall] hello", collector.messages().get(0));
        }

        @Test
        @DisplayName("a title/private-mode sequence does not leak a raw ESC")
        void otherSequencesAreStripped() {
            Collector collector = new Collector();
            inlineTap().addTap(collector);

            // jline emits these around the prompt on a terminal-attached server.
            tap.capture(event(Level.INFO,
                    ESC + "[?1h" + ESC + "]0;Minecraft" + BEL + "done"));

            assertFalse(collector.messages().get(0).contains(ESC),
                    "a raw ESC reaching the dashboard renders as mojibake: "
                            + collector.messages());
            assertEquals("done", collector.messages().get(0));
        }
    }

    @Nested
    @DisplayName("the bounded queue")
    class Bounded {

        @Test
        @DisplayName("a slow consumer cannot make the queue grow without bound")
        void oldestAreDropped() throws Exception {
            // No inline executor here: the queue only builds up when nothing is draining it, which
            // is exactly the state a stalled consumer produces.
            final CountDownLatch release = new CountDownLatch(1);
            pool = Executors.newSingleThreadExecutor();
            tap = new Log4jConsoleTap(logger, pool);
            final Collector collector = new Collector();
            tap.addTap(new Consumer<LogLine>() {
                @Override
                public void accept(LogLine line) {
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    collector.accept(line);
                }
            });

            // Comfortably past the 1000-line cap.
            for (int i = 0; i < 3000; i++) {
                tap.capture(event(Level.INFO, "line " + i));
            }
            release.countDown();

            // What is asserted is that the queue was bounded, not the exact survivors: the drain
            // runs concurrently with the producer, so which line is oldest at any moment is a race.
            // The bound is the promise; the identity of the casualties is not.
            long deadline = System.currentTimeMillis() + 5000L;
            while (System.currentTimeMillis() < deadline && collector.lines().size() < 1) {
                Thread.sleep(20);
            }
            assertTrue(collector.lines().size() < 3000,
                    "3000 lines through a 1000-line queue must lose some; a dashboard nobody is "
                            + "looking at must not cost memory");
        }
    }

    @Nested
    @DisplayName("nothing here can take the server down")
    class Containment {

        @Test
        @DisplayName("an event whose message throws is swallowed")
        void throwingEventIsContained() {
            Collector collector = new Collector();
            inlineTap().addTap(collector);

            LogEvent hostile = Log4jLogEvent.newBuilder()
                    .setLoggerName("test")
                    .setLevel(Level.INFO)
                    .setMessage(messageThat(new java.util.function.Supplier<String>() {
                        @Override
                        public String get() {
                            throw new IllegalStateException("a broken Message implementation");
                        }
                    }))
                    .build();

            assertDoesNotThrow(() -> tap.capture(hostile),
                    "an exception escaping the appender breaks the SERVER's logging, not just ours");
            assertTrue(collector.lines().isEmpty());
        }

        @Test
        @DisplayName("a null message is captured as empty rather than throwing")
        void nullMessageIsEmpty() {
            Collector collector = new Collector();
            inlineTap().addTap(collector);

            LogEvent nullMessage = Log4jLogEvent.newBuilder()
                    .setLoggerName("test")
                    .setLevel(Level.INFO)
                    .setMessage(messageThat(new java.util.function.Supplier<String>() {
                        @Override
                        public String get() {
                            return null;
                        }
                    }))
                    .build();

            tap.capture(nullMessage);

            assertEquals(Collections.singletonList(""), collector.messages());
        }

        @Test
        @DisplayName("a consumer that throws is dropped, and the others keep receiving")
        void throwingConsumerIsDropped() {
            Collector survivor = new Collector();
            inlineTap().addTap(new Consumer<LogLine>() {
                @Override
                public void accept(LogLine line) {
                    throw new IllegalStateException("a broken consumer");
                }
            });
            tap.addTap(survivor);

            tap.capture(event(Level.INFO, "first"));
            tap.capture(event(Level.INFO, "second"));

            assertEquals(java.util.Arrays.asList("first", "second"), survivor.messages(),
                    "one plugin's broken tap must not silence the console feed for everybody else");
        }

        @Test
        @DisplayName("a dropped consumer is counted, and reported once when asked")
        void droppedConsumersAreCounted() {
            inlineTap().addTap(new Consumer<LogLine>() {
                @Override
                public void accept(LogLine line) {
                    throw new IllegalStateException("a broken consumer");
                }
            });

            assertEquals(0, tap.droppedConsumers());
            tap.capture(event(Level.INFO, "first"));
            assertEquals(1, tap.droppedConsumers(),
                    "the drop is silent by necessity, so it has to be countable");

            assertTrue(tap.reportDroppedConsumers(), "the first ask should say something");
            assertFalse(tap.reportDroppedConsumers(),
                    "a tap that throws on every line would otherwise fill the log with the story "
                            + "of its own failure");
            assertTrue(logger.logged(com.heimdall.core.log.LogLevel.WARN, "unsubscribed"),
                    "the report has to name what stopped working: " + logger.records());
        }

        @Test
        @DisplayName("nothing to report when no consumer has misbehaved")
        void nothingToReport() {
            inlineTap().addTap(new Collector());
            tap.capture(event(Level.INFO, "fine"));

            assertEquals(0, tap.droppedConsumers());
            assertFalse(tap.reportDroppedConsumers());
        }

        @Test
        @DisplayName("capture never logs — logging from an appender re-enters log4j")
        void captureNeverLogs() {
            inlineTap().addTap(new Consumer<LogLine>() {
                @Override
                public void accept(LogLine line) {
                    throw new IllegalStateException("a broken consumer");
                }
            });

            tap.capture(event(Level.INFO, "anything"));

            assertTrue(logger.records().isEmpty(),
                    "a log from inside the capture path is delivered back into the capture path: "
                            + logger.records());
        }

        @Test
        @DisplayName("an executor that refuses work does not break capture")
        void rejectedExecutionIsContained() {
            pool = Executors.newSingleThreadExecutor();
            pool.shutdownNow();
            tap = new Log4jConsoleTap(logger, pool);
            tap.addTap(new Collector());

            assertDoesNotThrow(() -> tap.capture(event(Level.INFO, "during shutdown")),
                    "the pools stop before the server does; capture must survive that window");
        }
    }

    @Nested
    @DisplayName("subscription lifecycle")
    class Subscriptions {

        @Test
        @DisplayName("with no taps registered nothing is even built")
        void noTapsMeansNoWork() {
            inlineTap();

            tap.capture(event(Level.INFO, "nobody is listening"));

            // Nothing to assert against a consumer, so the promise is the negative one: it did not
            // throw, and close() below finds an empty queue.
            assertFalse(tap.isAttached());
        }

        @Test
        @DisplayName("closing a registration stops delivery to that consumer only")
        void unsubscribeIsPerConsumer() {
            Collector staying = new Collector();
            Collector leaving = new Collector();
            inlineTap().addTap(staying);
            Registration handle = tap.addTap(leaving);

            tap.capture(event(Level.INFO, "both"));
            handle.close();
            tap.capture(event(Level.INFO, "only one"));
            handle.close();

            assertEquals(java.util.Arrays.asList("both", "only one"), staying.messages());
            assertEquals(Collections.singletonList("both"), leaving.messages());
        }

        @Test
        @DisplayName("close drops the taps and the buffer")
        void closeClearsEverything() {
            Collector collector = new Collector();
            inlineTap().addTap(collector);

            tap.close();
            tap.capture(event(Level.INFO, "after close"));
            tap.close();

            assertTrue(collector.lines().isEmpty());
        }
    }

    @Test
    @DisplayName("delivery happens off the capturing thread")
    void deliveryIsAsynchronous() throws Exception {
        pool = Executors.newSingleThreadExecutor();
        tap = new Log4jConsoleTap(logger, pool);
        final CountDownLatch delivered = new CountDownLatch(1);
        final List<String> threads = Collections.synchronizedList(new ArrayList<String>());
        tap.addTap(new Consumer<LogLine>() {
            @Override
            public void accept(LogLine line) {
                threads.add(Thread.currentThread().getName());
                delivered.countDown();
            }
        });

        String capturingThread = Thread.currentThread().getName();
        tap.capture(event(Level.INFO, "off-thread"));

        assertTrue(delivered.await(5, TimeUnit.SECONDS), "the line was never delivered");
        assertFalse(threads.contains(capturingThread),
                "fan-out on the logging thread puts a plugin's callback on the critical path of "
                        + "every line the server writes");
    }
}
