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

/**
 * Watching everything the server prints, in the part of it that is not about a logging framework.
 *
 * <p>Two platform families, two logging backends. The Bukkit family and Velocity run log4j2 — Mojang
 * has bundled it since 1.7 — and BungeeCord runs {@code java.util.logging} and nothing else. What
 * differs between them is three things: how a sink is attached, how one line is turned into a level
 * and a string, and how a probe line is emitted. <em>Everything</em> else — the level floor's
 * consequences, the ANSI strip, the bounded queue, the drain executor, the re-entrancy guard, the
 * dropped-consumer accounting, the attach-time self-test — is identical, and this class is where it
 * lives exactly once.
 *
 * <p>That split is not tidiness. v2 wrote its LuckPerms bridge twice and the copies drifted
 * (departure D46); a console tap written twice would drift in the places that took the longest to get
 * right — the ANSI pattern that used to eat ordinary brackets, the appender that must be removed but
 * never stopped, the guard that stops a consumer feeding itself.
 *
 * <h2>Never able to take the server down</h2>
 *
 * <p>{@link #capture} runs inside the server's logging pipeline, on whatever thread the backend
 * delivers on, and obeys three rules absolutely:
 *
 * <ul>
 *   <li><strong>It never logs.</strong> Logging from inside a sink re-enters the logging framework,
 *       which calls the sink again. The same applies to the consumers, which is why they run
 *       somewhere else behind a re-entrancy guard rather than inline.
 *   <li><strong>It never throws.</strong> Everything is wrapped; a broken tap must degrade to a
 *       missing console feed, not to a server that cannot log.
 *   <li><strong>It never blocks and never grows.</strong> A bounded queue of
 *       {@value #MAX_QUEUE_SIZE} lines, dropping the oldest on overflow. A dashboard nobody is
 *       looking at must not cost memory.
 * </ul>
 *
 * <p>Fan-out happens on the supplied executor ({@code heimdall-io} in production) rather than on the
 * logging thread, so a slow consumer costs Heimdall latency rather than costing the server tick
 * rate.
 *
 * <p>With no taps registered the sink stays attached but discards immediately, so switching the
 * console module on and off does not repeatedly attach and detach a root appender.
 *
 * <h2>The re-entrancy guard is thread-local, and that is weaker than it sounds</h2>
 *
 * <p>{@link #DELIVERING} is set on the thread running a consumer, so a consumer that logs
 * <em>synchronously</em> back into the same thread is caught. It cannot catch a backend that hands
 * log records to a thread of its own — Paper's async loggers, and BungeeCord's {@code LogDispatcher},
 * which is <strong>always</strong> asynchronous. On those, a consumer that logs produces a line that
 * arrives on the backend's thread with no guard set, and feeds itself forever.
 *
 * <p>That is why {@link com.heimdall.core.platform.ConsoleBridge} states "a consumer must not log"
 * as a rule rather than a suggestion: the guard is a second line of defence for the one case it can
 * see, not the reason the rule exists. Said here plainly because the alternative is a comment that
 * claims a safety property the code does not have — which is the class of thing this codebase has
 * corrected twice already.
 */
public abstract class ConsoleTap implements AutoCloseable {

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

    /** Distinct names, so two instances in one JVM (a reload) cannot remove each other's sink. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** How long the probe waits to come back around. See {@link #captureWorks()}. */
    private static final long SELF_TEST_TIMEOUT_MS = 2000L;

    /**
     * Makes each probe line unique, so a stale one cannot satisfy a later attach.
     *
     * <p>Seeded from the clock and <strong>incremented per attach</strong>. Both halves are needed
     * and it previously had only the first: the seed keeps two JVMs apart, and the increment keeps
     * two attaches within one JVM apart — which is the case that matters, because that is a
     * {@code /reload}, and the queue it would be satisfied from is the one the previous instance
     * left behind.
     */
    private static final AtomicLong SELF_TEST_NONCE = new AtomicLong(System.nanoTime());

    /** Set while a consumer is running, so a consumer that logs does not feed itself. */
    private static final ThreadLocal<Boolean> DELIVERING = new ThreadLocal<Boolean>();

    /** For a subclass that needs to say something outside the capture path. Never used inside it. */
    protected final HeimdallLogger logger;

    private final Executor executor;

    /** This instance's name, unique within the JVM. Subclasses use it to name their sink. */
    private final String instanceName = "HeimdallConsoleTap-" + SEQUENCE.incrementAndGet();

    private final CopyOnWriteArrayList<Consumer<LogLine>> taps =
            new CopyOnWriteArrayList<Consumer<LogLine>>();
    private final ConcurrentLinkedQueue<LogLine> queue = new ConcurrentLinkedQueue<LogLine>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicBoolean draining = new AtomicBoolean();

    /**
     * How many consumers have been dropped for throwing, ever.
     *
     * <p>Counted because the drop is otherwise completely silent — by necessity: the drop happens
     * inside the delivery guard, where logging would be captured and fed back into the loop the
     * guard exists to break. So the count is recorded here and reported from
     * {@link #droppedConsumers()}, out of band, by whoever is in a position to say something.
     *
     * <p>Reported by {@code /hd status}, through {@code ConsoleBridge.droppedTapConsumers()}. That
     * indirection exists because this is the only number in the plugin that cannot report itself:
     * "the dashboard console went quiet" and "a plugin's tap threw once and was unsubscribed" are
     * otherwise identical from every angle an operator has.
     */
    private final AtomicInteger droppedConsumers = new AtomicInteger();

    /** Whether a dropped consumer has already been mentioned. One WARN, not one per drop. */
    private final AtomicBoolean warnedAboutDrops = new AtomicBoolean();

    /** Whether {@link #attachSink()} has succeeded and {@link #detachSink()} has not yet run. */
    private volatile boolean attached;

    /**
     * @param executor where consumers run; {@code heimdall-io} in production
     */
    protected ConsoleTap(HeimdallLogger logger, Executor executor) {
        if (logger == null || executor == null) {
            throw new IllegalArgumentException("logger and executor are required");
        }
        this.logger = logger;
        this.executor = executor;
    }

    // ── What a platform has to supply ────────────────────────────────────────

    /**
     * Attaches this tap's sink to the platform's logging backend.
     *
     * <p>Called at most once per attach, under this instance's monitor. Throwing is the way to say
     * "not attachable here" — the caller catches {@link Throwable}, because the interesting failure
     * on a decade-old server is a {@link NoSuchMethodError} rather than an exception.
     */
    protected abstract void attachSink() throws Exception;

    /**
     * Detaches the sink. Best-effort: the caller contains anything thrown, because a sink left
     * attached with no taps discards every line immediately and is inert, whereas an exception
     * during plugin disable is not.
     */
    protected abstract void detachSink();

    /**
     * Logs one line through the platform's <em>own</em> logger, so that a working tap receives it.
     *
     * <p>Deliberately not through {@link HeimdallLogger}: the point of the probe is to exercise the
     * real backend path, and on some platforms Heimdall's own logger is bridged onto a different one.
     */
    protected abstract void emitProbe(String marker) throws Exception;

    /**
     * What to tell an operator whose sink attached but did not capture its own probe.
     *
     * <p>Platform-specific because the two causes are: a log4j whose API shape is older than the one
     * compiled against, and a logger that does not deliver to handlers attached to it. Those have
     * different fixes and deserve different sentences.
     */
    protected abstract String deafExplanation();

    /** This instance's name, unique within the JVM. For naming the platform's sink. */
    protected final String instanceName() {
        return instanceName;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Attaches the sink and proves it captures.
     *
     * <p>Idempotent, and never throws: a platform whose logging backend is not attachable gets
     * {@code false} and a degraded console, not a failed boot.
     *
     * <h2>Attaching is not the same as working</h2>
     *
     * <p>The attach itself only registers a sink. The calls this class exists to be careful about —
     * reading a level, formatting a message — all live in the subclass's capture method, and none of
     * them runs until something is actually logged <em>with a tap registered</em>. On a booting
     * server nothing has registered one yet, so an attach that returned true proved only that a
     * method existed.
     *
     * <p>That is the difference between "the sink is on the logger" and "this server's logging
     * backend speaks the API we compiled against", and on Minecraft 1.8.8's log4j {@code 2.0-beta9}
     * those are genuinely different questions (departure D45). So the attach ends by logging one line
     * through the backend and waiting to receive it back through the whole path: filter, ANSI strip,
     * queue, drain executor, consumer. Only then does it report success — which is what makes the
     * smoke matrix's one-line {@code console tap on} assertion transitively cover all of it, on
     * every supported server, every run.
     *
     * @return whether the tap is live <em>and</em> demonstrably capturing
     */
    public final synchronized boolean attach() {
        if (attached) {
            return true;
        }
        try {
            attachSink();
            attached = true;
        } catch (Throwable notAttachable) {
            logger.warn("console tap unavailable on this server: " + notAttachable);
            return false;
        }
        if (captureWorks()) {
            return true;
        }
        // Attached but deaf. Detach rather than leave a dead sink on the logger, and say which half
        // failed — "attached" and "capturing" have different causes and different fixes.
        logger.warn("console tap attached but did not capture its own probe line; " + deafExplanation());
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
        // incrementAndGet, not get: see the field. Reading it left every attach in one JVM using the
        // same marker, which is exactly the reload case the nonce exists for.
        final String marker =
                "console tap self-test " + Long.toHexString(SELF_TEST_NONCE.incrementAndGet());
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
            emitProbe(marker);
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

    /** Whether the sink is currently attached. */
    public final boolean isAttached() {
        return attached;
    }

    /**
     * How many consumers have been unsubscribed for throwing.
     *
     * <p>Read from outside the delivery guard, which is the only place it is safe to say anything
     * about. A non-zero count means somebody's console feed stopped without anybody being told —
     * see the field for why the drop itself cannot log.
     */
    public final int droppedConsumers() {
        return droppedConsumers.get();
    }

    /**
     * Logs once, at WARN, if any consumer has been dropped since the last time this was asked to.
     *
     * <p>Called from outside the capture path — a scheduled sweep, or a status command — never from
     * inside it. Once, not once per drop: a tap that throws on every line would otherwise fill the
     * log with the story of its own failure.
     *
     * @return whether a warning was emitted
     */
    public final boolean reportDroppedConsumers() {
        int dropped = droppedConsumers.get();
        if (dropped == 0 || !warnedAboutDrops.compareAndSet(false, true)) {
            return false;
        }
        logger.warn(dropped + " console consumer(s) threw and were unsubscribed; whatever was "
                + "reading the console feed has stopped receiving it");
        return true;
    }

    /**
     * Subscribes to the console feed.
     *
     * <p><strong>A consumer must not call {@link #attach()} or {@link #close()}.</strong> Both are
     * {@code synchronized} on this instance and a consumer runs on the drain thread, outside that
     * lock: a consumer that called one while another thread held it would block the drain waiting
     * for a lock whose holder is, in {@code attach()}'s case, waiting on the drain for its own
     * self-test probe. Detaching in response to a line is a reasonable thing to want — closing the
     * {@code Registration} this returns is safe from anywhere, including from inside a consumer,
     * because that only touches a copy-on-write list.
     *
     * @return a handle that unsubscribes; closing it twice is a no-op
     */
    public final Registration addTap(final Consumer<LogLine> consumer) {
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
     * Detaches the sink and drops anything buffered. Idempotent.
     *
     * <p>The taps and the queue are cleared <em>before</em> the sink is detached, so anything the
     * backend is still delivering — an event that entered an async logger's ring buffer before the
     * detach — arrives at a tap with nothing subscribed and is discarded silently. See
     * {@link Log4jConsoleTap#detachSink()} for why that ordering is load-bearing on log4j
     * specifically.
     */
    @Override
    public final synchronized void close() {
        boolean wasAttached = attached;
        attached = false;
        taps.clear();
        queue.clear();
        queued.set(0);
        if (!wasAttached) {
            return;
        }
        try {
            detachSink();
        } catch (Throwable ignored) {
            // Detaching is best-effort. A sink left attached with no taps discards every line
            // immediately, which is inert — a thrown exception during plugin disable is not.
        }
    }

    // ── The capture path. Nothing here logs, throws or blocks. ───────────────

    /**
     * Whether it is worth building a line at all.
     *
     * <p>Called by a subclass <em>before</em> it formats a message, which is the expensive half on
     * both backends: log4j's {@code getFormattedMessage()} renders parameters, and JUL's may run a
     * {@code MessageFormat}. {@link #capture} re-checks, because the answer can change in between
     * and the check is two field reads.
     */
    protected final boolean capturing() {
        return !taps.isEmpty() && !Boolean.TRUE.equals(DELIVERING.get());
    }

    /**
     * Accepts one already-rendered line: strip, queue, and schedule a drain.
     *
     * <p>The <em>level filter</em> is deliberately the subclass's job rather than this method's.
     * "INFO and above" is a different comparison on each backend — log4j's severities run
     * more-severe = numerically lower, JUL's run the other way — and a shared method taking an
     * already-decided answer cannot get that backwards for one of them.
     *
     * <p>The timestamp is taken here rather than read off the backend's own record, deliberately:
     * {@code LogEvent.getTimeMillis()} arrived in log4j 2.4 and Minecraft 1.8.8 ships 2.0-beta9,
     * which only has {@code getMillis()}. Rather than reflect over two spellings on one platform and
     * read a field on the other, every line is stamped at the moment it is captured — which is the
     * same instant to within the time it takes the backend to call a sink.
     *
     * @param levelName the backend's own name for the level, e.g. {@code INFO}
     * @param rawMessage the rendered message; {@code null} becomes empty
     */
    protected final void capture(String levelName, String rawMessage) {
        try {
            if (!capturing()) {
                return;
            }
            LogLine line = new LogLine(
                    System.currentTimeMillis(),
                    levelName,
                    rawMessage == null ? "" : ANSI.matcher(rawMessage).replaceAll(""));

            queue.add(line);
            if (queued.incrementAndGet() > MAX_QUEUE_SIZE && queue.poll() != null) {
                queued.decrementAndGet();
            }
            scheduleDrain();
        } catch (Throwable ignored) {
            // A broken sink must not break the server's logging.
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
                        // Contained, and still not logged here: this runs under the delivery guard,
                        // so a log would be captured and could feed the loop the guard exists to
                        // break. Counted instead, and reported by whoever asks.
                        taps.remove(tap);
                        droppedConsumers.incrementAndGet();
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
}
