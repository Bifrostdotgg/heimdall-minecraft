package com.heimdall.platform.common;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.LogLine;
import com.heimdall.core.util.Registration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <em>own</em> console appender sees. The {@code INFO} floor is applied inside {@link #enqueue}
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
 * <p>{@link #enqueue} runs inside the server's logging pipeline, on whatever thread emitted the
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
     * <p>Matching the leading ESC is the fix for a bug v2 shipped: its pattern began at
     * {@code [}, so it also matched ordinary bracketed text — {@code [12:00:00 INFO]} lost its
     * closing bracket on the way to Discord.
     */
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[;\\d]*[ -/]*[@-~]");

    /** Distinct names, so two instances in one JVM (a reload) cannot remove each other's appender. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

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
     * Attaches the appender to the root logger.
     *
     * <p>Idempotent, and never throws: a platform whose logging backend is not attachable log4j2
     * gets {@code false} and a degraded console, not a failed boot.
     *
     * @return whether the tap is live
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
            return true;
        } catch (Throwable notAttachable) {
            logger.warn("console tap unavailable on this server: " + notAttachable);
            return false;
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

    /** Detaches the appender and drops anything buffered. Idempotent. */
    @Override
    public synchronized void close() {
        AbstractAppender attached = this.appender;
        LoggerConfig root = this.attachedTo;
        this.appender = null;
        this.attachedTo = null;
        taps.clear();
        queue.clear();
        queued.set(0);
        if (attached == null) {
            return;
        }
        try {
            if (root != null) {
                root.removeAppender(appenderName);
            }
            attached.stop();
        } catch (Throwable ignored) {
            // Detaching is best-effort. An appender left attached with no taps discards every line
            // immediately, which is inert — a thrown exception during plugin disable is not.
        }
    }

    // ── The capture path. Nothing here logs, throws or blocks. ───────────────

    private void enqueue(LogEvent event) {
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
            enqueue(event);
        }
    }
}
