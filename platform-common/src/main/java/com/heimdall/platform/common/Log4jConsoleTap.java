package com.heimdall.platform.common;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.LogLine;
import com.heimdall.core.util.Registration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;

/**
 * Watches everything the server prints, on both platforms, without being able to break either.
 *
 * <p>The Bukkit family and Velocity both run log4j2 — Mojang has bundled it since 1.7 — so a root
 * appender is one implementation for both. It is attached to the root {@link LoggerConfig} with no
 * level filter of its own, deliberately: setting a level there would change what the server's
 * <em>own</em> console appender sees. The {@code INFO} floor is applied inside {@link #capture}
 * instead.
 *
 * <h2>The API surface is chosen for Minecraft 1.8.8, not for the version this compiles against</h2>
 *
 * <p>1.8.8 ships log4j <strong>2.0-beta9</strong>. Two consequences, and both are the reason this
 * class is not simply v2's {@code ConsoleStreamer} moved:
 *
 * <ul>
 *   <li>v2 used the five-argument {@code AbstractAppender(name, filter, layout, ignoreExceptions,
 *       properties)} constructor, which did not exist until 2.11.2. On 1.8.8 that is a
 *       {@code NoSuchMethodError} the first time console streaming is switched on. The four-argument
 *       form used here is deprecated on modern log4j but present in every 2.x, which is the only
 *       property that matters when the runtime version spans a decade.
 *   <li>{@code LogEvent.getTimeMillis()} arrived in 2.4; beta9 only has {@code getMillis()}. Rather
 *       than reflect over two spellings, the line is stamped with {@link System#currentTimeMillis()}
 *       at the moment it is captured — which is the same instant to within the time it takes log4j
 *       to call an appender.
 * </ul>
 *
 * <h2>Never able to take the server down</h2>
 *
 * <p>{@link #capture} runs inside the server's logging pipeline, on whatever thread emitted the
 * line, and obeys three rules absolutely:
 *
 * <ul>
 *   <li><strong>It never logs.</strong> Logging from an appender re-enters log4j, which calls the
 *       appender again. The same applies to the consumers, which is why they run somewhere else
 *       behind a re-entrancy guard rather than inline.
 *   <li><strong>It never throws.</strong> Everything is wrapped; a broken tap must degrade to a
 *       missing console feed, not to a server that cannot log.
 *   <li><strong>It never blocks and never grows.</strong> A bounded queue of
 *       {@value #MAX_QUEUE_SIZE} lines, dropping the oldest on overflow. A dashboard nobody is
 *       looking at must not cost memory.
 * </ul>
 *
 * <p>Fan-out happens on the supplied executor ({@code heimdall-io} in production) rather than on the
 * logging thread, so a slow consumer costs Heimdall latency rather than costing the server tick
 * rate. A thread-local guard suppresses capture while a consumer is running, which is what stops a
 * consumer that logs from feeding itself forever.
 *
 * <p>With no taps registered the appender stays attached but discards immediately, so switching the
 * console module on and off does not repeatedly attach and detach a root appender.
 */
public final class Log4jConsoleTap implements AutoCloseable {

    /** Hard cap on buffered lines. The oldest are dropped once exceeded. */
    private static final int MAX_QUEUE_SIZE = 1000;

    /** Lines fanned out per drain pass, so one drain cannot monopolise an IO thread. */
    private static final int MAX_BATCH_SIZE = 200;

    /**
     * ANSI/VT100 escape sequences, stripped so the dashboard does not render them as mojibake.
     *
     * <p>Three alternatives, because a Minecraft server console emits three shapes:
     *
     * <ul>
     *   <li><strong>CSI</strong> — {@code ESC [ params intermediates final}. The colour codes, and
     *       the only shape v2 tried to handle. Parameter bytes are {@code [0-?]} per ECMA-48, not
     *       just digits and semicolons: {@code ESC[?1h} (jline turning on a private mode) has a
     *       {@code ?} in the parameter position, and a digits-only class stops matching at it and
     *       leaves the whole sequence in the line.
     *   <li><strong>OSC</strong> — {@code ESC ] text BEL} or {@code ESC ] text ESC \}. jline sets
     *       the terminal title this way on an interactive server, and the payload is arbitrary
     *       text, so nothing but an explicit terminator ends it.
     *   <li><strong>Two-character escapes</strong> — {@code ESC} followed by one byte in
     *       {@code @}–{@code _}. Last, so CSI and OSC claim their introducers first; this catches
     *       the leftovers, including an OSC somebody truncated.
     * </ul>
     *
     * <p>Matching the leading ESC at all is the fix for a bug v2 shipped: its pattern began at the
     * {@code [}, so it also matched ordinary bracketed text — {@code [12:00:00 INFO]} lost its
     * closing bracket on the way to Discord.
     */
    private static final Pattern ANSI = Pattern.compile(
            "\\x1B\\[[0-?]*[ -/]*[@-~]"
                    + "|\\x1B\\][^\\x07\\x1B]*(?:\\x07|\\x1B\\\\)"
                    + "|\\x1B[@-_]");

    /** Distinct names, so two instances in one JVM (a reload) cannot remove each other's appender. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** The logger the attach-time probe line is emitted through. */
    private static final String SELF_TEST_LOGGER = "com.heimdall.consoletap";

    /** How long the probe waits to come back around. See {@link #captureWorks()}. */
    private static final long SELF_TEST_TIMEOUT_MS = 2000L;

    /** Makes each probe line unique, so a stale one cannot satisfy a later attach. */
    private static final AtomicLong SELF_TEST_NONCE = new AtomicLong(System.nanoTime());

    /** Set while a consumer is running, so a consumer that logs does not feed itself. */
    private static final ThreadLocal<Boolean> DELIVERING = new ThreadLocal<Boolean>();

    private final HeimdallLogger logger;
    private final Executor executor;
    private final String appenderName = "HeimdallConsoleTap-" + SEQUENCE.incrementAndGet();

    private final CopyOnWriteArrayList<Consumer<LogLine>> taps =
            new CopyOnWriteArrayList<Consumer<LogLine>>();
    private final ConcurrentLinkedQueue<LogLine> queue = new ConcurrentLinkedQueue<LogLine>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicBoolean draining = new AtomicBoolean();

    private volatile AbstractAppender appender;
    private volatile LoggerConfig attachedTo;

    /**
     * @param executor where consumers run; {@code heimdall-io} in production
     */
    public Log4jConsoleTap(HeimdallLogger logger, Executor executor) {
        if (logger == null || executor == null) {
            throw new IllegalArgumentException("logger and executor are required");
        }
        this.logger = logger;
        this.executor = executor;
    }

    /**
     * Attaches the appender to the root logger and proves it captures.
     *
     * <p>Idempotent, and never throws: a platform whose logging backend is not attachable log4j2
     * gets {@code false} and a degraded console, not a failed boot.
     *
     * <h2>Attaching is not the same as working</h2>
     *
     * <p>The attach itself only calls {@code addAppender}, which every Log4j 2.x has. The calls
     * this class exists to be careful about — {@code event.getLevel().intLevel()},
     * {@code event.getMessage().getFormattedMessage()}, {@code Level.name()} — all live in
     * {@link #capture}, and none of them runs until something is actually logged <em>with a tap
     * registered</em>. On a booting server nothing has registered one yet, so an attach that
     * returned true proved only that a method existed.
     *
     * <p>That is the difference between "the appender is on the root logger" and "this server's
     * Log4j speaks the API we compiled against", and on Minecraft 1.8.8's {@code 2.0-beta9} those
     * are genuinely different questions (departure D45). So the attach ends by logging one line
     * through Log4j and waiting to receive it back through the whole path: filter, ANSI strip,
     * queue, drain executor, consumer. Only then does it report success — which is what makes the
     * smoke matrix's one-line {@code console tap on} assertion transitively cover all of it, on
     * every supported server, every run.
     *
     * @return whether the tap is live <em>and</em> demonstrably capturing
     */
    public synchronized boolean attach() {
        if (appender != null) {
            return true;
        }
        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            LoggerConfig root = context.getConfiguration().getRootLogger();
            AbstractAppender attached = new TapAppender(appenderName);
            attached.start();
            root.addAppender(attached, null, null);
            this.appender = attached;
            this.attachedTo = root;
        } catch (Throwable notAttachable) {
            logger.warn("console tap unavailable on this server: " + notAttachable);
            return false;
        }
        if (captureWorks()) {
            return true;
        }
        // Attached but deaf. Detach rather than leave a dead appender on the root logger, and say
        // which half failed — "attached" and "capturing" have different causes and different fixes.
        logger.warn("console tap attached but did not capture its own probe line; this server's "
                + "log4j does not speak the API Heimdall compiled against, so console streaming "
                + "is off");
        close();
        return false;
    }

    /**
     * Logs one line and waits to receive it back through the full capture path.
     *
     * <p>The nonce matters: without it a line left in the queue by an earlier attach — a
     * {@code /reload} on the same JVM — could satisfy a later probe that the capture path would
     * have failed.
     *
     * <p>Bounded, and a timeout is a failure rather than a hang. This runs on the server's main
     * thread during enable, so the budget is small; {@code heimdall-io} is idle at that point, and
     * a pool too busy to run one task in two seconds at boot is a tap that would drop lines anyway.
     */
    private boolean captureWorks() {
        final String marker = "console tap self-test " + Long.toHexString(SELF_TEST_NONCE.get());
        final CountDownLatch received = new CountDownLatch(1);
        Registration probe = addTap(new Consumer<LogLine>() {
            @Override
            public void accept(LogLine line) {
                if (line.message().contains(marker)) {
                    received.countDown();
                }
            }
        });
        try {
            // Through Log4j itself, not through Heimdall's own logger: the point is to exercise the
            // real appender path, and on Bukkit the plugin logger is JUL bridged into log4j, which
            // would prove a different thing.
            LogManager.getLogger(SELF_TEST_LOGGER).info(marker);
            return received.await(SELF_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable unusable) {
            logger.debug(() -> "console tap self-test failed: " + unusable);
            return false;
        } finally {
            probe.close();
        }
    }

    /** Whether the appender is currently attached. */
    public boolean isAttached() {
        return appender != null;
    }

    /**
     * Subscribes to the console feed.
     *
     * @return a handle that unsubscribes; closing it twice is a no-op
     */
    public Registration addTap(final Consumer<LogLine> consumer) {
        if (consumer == null) {
            return Registration.NONE;
        }
        taps.add(consumer);
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                taps.remove(consumer);
            }
        });
    }

    /**
     * Detaches the appender and drops anything buffered. Idempotent.
     *
     * <h2>The appender is removed but deliberately never stopped</h2>
     *
     * <p>That looks like a leak and is the opposite. On a server with async loggers — Paper 1.16.5
     * and every version since — log events are queued and delivered on a background thread, so an
     * event that entered the ring buffer <em>before</em> the appender was removed can be delivered
     * after. Log4j's {@code AppenderControl} then finds a stopped appender and its status logger
     * prints {@code ERROR Attempted to append to non-started appender HeimdallConsoleTap-N} — an
     * ERROR line, in the server's log, naming Heimdall, during shutdown, caused by nothing being
     * wrong. It failed the smoke matrix's own error check, which is exactly the outcome the check
     * exists to produce for a real fault.
     *
     * <p>Removing without stopping closes that window: {@code LoggerConfig.removeAppender} does not
     * stop the appender, so anything still in flight arrives at a started appender that discards it
     * (the taps are cleared above) and nothing is logged. An {@link AbstractAppender} that is never
     * stopped holds no resources — no thread, no handle, no buffer beyond the queue this method has
     * just emptied — so there is nothing left to release.
     */
    @Override
    public synchronized void close() {
        AbstractAppender attached = this.appender;
        LoggerConfig root = this.attachedTo;
        this.appender = null;
        this.attachedTo = null;
        taps.clear();
        queue.clear();
        queued.set(0);
        if (attached == null || root == null) {
            return;
        }
        try {
            root.removeAppender(appenderName);
        } catch (Throwable ignored) {
            // Detaching is best-effort. An appender left attached with no taps discards every line
            // immediately, which is inert — a thrown exception during plugin disable is not.
        }
    }

    // ── The capture path. Nothing here logs, throws or blocks. ───────────────

    void capture(LogEvent event) {
        try {
            if (taps.isEmpty() || Boolean.TRUE.equals(DELIVERING.get())) {
                return;
            }
            // Log4j levels run more-severe = lower, so INFO's intLevel is the ceiling we accept.
            if (event.getLevel().intLevel() > Level.INFO.intLevel()) {
                return;
            }
            String raw = event.getMessage().getFormattedMessage();
            LogLine line = new LogLine(
                    System.currentTimeMillis(),
                    event.getLevel().name(),
                    raw == null ? "" : ANSI.matcher(raw).replaceAll(""));

            queue.add(line);
            if (queued.incrementAndGet() > MAX_QUEUE_SIZE && queue.poll() != null) {
                queued.decrementAndGet();
            }
            scheduleDrain();
        } catch (Throwable ignored) {
            // A broken append() must not break the server's logging.
        }
    }

    private void scheduleDrain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    drain();
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            draining.set(false);
        }
    }

    private void drain() {
        try {
            DELIVERING.set(Boolean.TRUE);
            for (int i = 0; i < MAX_BATCH_SIZE; i++) {
                LogLine line = queue.poll();
                if (line == null) {
                    break;
                }
                queued.decrementAndGet();
                for (Consumer<LogLine> tap : taps) {
                    try {
                        tap.accept(line);
                    } catch (RuntimeException broken) {
                        // Contained, and still not logged: this runs under the delivery guard, so a
                        // log here would be captured and could feed the loop it is guarding against.
                        taps.remove(tap);
                    }
                }
            }
        } finally {
            DELIVERING.remove();
            draining.set(false);
            // Anything that arrived while this pass was running needs another one, and the arriving
            // thread saw the flag set and did not schedule it.
            if (!queue.isEmpty() && !taps.isEmpty()) {
                scheduleDrain();
            }
        }
    }

    /**
     * The appender itself.
     *
     * <p>A named inner class rather than an anonymous one so its constructor call is legible: the
     * four-argument super is the compatibility decision this whole file exists to make.
     */
    private final class TapAppender extends AbstractAppender {

        @SuppressWarnings("deprecation")
        TapAppender(String name) {
            // (name, filter, layout, ignoreExceptions). Deprecated on modern log4j, present in
            // every 2.x including the 2.0-beta9 that Minecraft 1.8.8 ships. The five-argument
            // overload v2 used arrived in 2.11.2 and would be a NoSuchMethodError there.
            super(name, null, null, true);
        }

        @Override
        public void append(LogEvent event) {
            capture(event);
        }
    }
}
