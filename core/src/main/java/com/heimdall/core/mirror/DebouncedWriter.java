package com.heimdall.core.mirror;

import com.heimdall.core.log.HeimdallLogger;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns a burst of mutations into one write, on somebody else's thread.
 *
 * <p>This exists because of a specific v2 defect. {@code WhitelistCache} called {@code saveCache()}
 * — a full, synchronous, whole-file Gson serialisation — from {@code isCachedWhitelisted},
 * {@code addWhitelistedPlayer}, {@code extendCacheOnJoin}, {@code extendCacheOnLeave},
 * {@code reconcileFromSync} and {@code cleanupExpiredEntries}. Several of those run on the login
 * thread, so every join and every leave rewrote the entire whitelist file inline, and a pre-warm
 * sync of a few thousand players did it once per player.
 *
 * <p>Here a mutation only sets a flag and, at most once per debounce window, schedules the write
 * onto the supplied scheduler. {@link #flush()} is synchronous and is what shutdown calls, so
 * nothing is lost on the way out.
 *
 * <p>Thread-safe. Writes are serialised on an internal lock, so the write action never runs
 * concurrently with itself.
 */
final class DebouncedWriter implements AutoCloseable {

    private final HeimdallLogger logger;
    private final ScheduledExecutorService scheduler;
    private final long debounceMs;
    private final Runnable write;
    private final String description;

    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicLong writes = new AtomicLong();
    private final Object writeLock = new Object();

    private volatile boolean closed;

    DebouncedWriter(
            HeimdallLogger logger,
            ScheduledExecutorService scheduler,
            long debounceMs,
            String description,
            Runnable write) {
        if (logger == null || write == null) {
            throw new IllegalArgumentException("logger and write action are required");
        }
        if (debounceMs > 0 && scheduler == null) {
            throw new IllegalArgumentException("a debounced writer needs a scheduler");
        }
        this.logger = logger;
        this.scheduler = scheduler;
        this.debounceMs = debounceMs;
        this.description = description;
        this.write = write;
    }

    /**
     * Records that there is something to write.
     *
     * <p>Returns immediately. The actual write happens up to {@code debounceMs} later, or inline if
     * the writer was built with no debounce, or inline if the scheduler has already stopped
     * accepting work — losing the change would be worse than blocking the caller once.
     */
    void markDirty() {
        dirty.set(true);
        if (closed || debounceMs <= 0) {
            flush();
            return;
        }
        if (!scheduled.compareAndSet(false, true)) {
            // A write is already pending and will pick this change up: that is the coalescing.
            return;
        }
        try {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    // Cleared before the write, so a mutation arriving during it schedules the
                    // next one rather than being folded into a write that has already snapshotted.
                    scheduled.set(false);
                    flush();
                }
            }, debounceMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            scheduled.set(false);
            flush();
        }
    }

    /**
     * Writes now, if there is anything to write.
     *
     * <p>A failed write leaves the dirty flag set, so the next flush retries rather than silently
     * dropping the change.
     */
    void flush() {
        synchronized (writeLock) {
            if (!dirty.getAndSet(false)) {
                return;
            }
            try {
                write.run();
                writes.incrementAndGet();
            } catch (RuntimeException e) {
                dirty.set(true);
                logger.error("Could not save " + description, e);
            }
        }
    }

    /** Flushes and stops scheduling. Further mutations write inline. */
    @Override
    public void close() {
        closed = true;
        flush();
    }

    /** How many writes have actually happened. Diagnostics and tests. */
    long writeCount() {
        return writes.get();
    }
}
