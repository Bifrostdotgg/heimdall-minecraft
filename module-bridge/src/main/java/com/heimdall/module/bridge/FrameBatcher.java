package com.heimdall.module.bridge;

import com.heimdall.core.json.Payload;
import com.heimdall.core.tunnel.TunnelBus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A bounded queue that ships what it holds as one frame per drain, and throws away what it cannot.
 *
 * <p>The bridge drains it the moment something is queued (see {@code HeimdallBridgeModule}'s
 * immediate drain), and on a one-second tick as a safety net, so a quiet server's lines leave at
 * once and a busy one's ride together in whatever frame is next.
 *
 * <p>Every mechanic here is {@code HeimdallConsoleModule}'s, transcribed rather than reinvented: a
 * one-second flush, a hard queue cap with drop-oldest, a per-flush batch cap, and drain-and-discard
 * while the tunnel is down. That module has run those numbers in production since v2 (as
 * {@code ConsoleStreamer}), so the bridge inherits behaviour that is already known to hold under a
 * loud server rather than a second opinion about it.
 *
 * <p>It is a class rather than a copy because the bridge needs the same machinery <em>twice</em>,
 * for {@code bridge.chat} and {@code bridge.event}. Two hand-written copies would be two places for
 * the bound to drift, and the failure mode of a drifted bound — a queue that grows while the bot is
 * away — is invisible until an out-of-memory hours later.
 *
 * <h2>Drain-and-discard is not a bug</h2>
 *
 * <p>{@link #flush} always removes up to {@code maxBatch} items, whether or not the tunnel is
 * connected, and only decides <em>after</em> draining whether to ship them. A drained batch is never
 * put back. The alternative — draining only once a bot is there to receive it — is exactly how a
 * queue grows without bound while a bot is down, which is the one thing every bound here exists to
 * prevent.
 *
 * <p>For this module the cost of that is smaller than it is for the console: chat produced while
 * Discord is unreachable is chat that <em>already happened in the game</em>, in front of the people
 * it was addressed to. Relaying five minutes of it in a burst on reconnect would be worse than
 * losing it — see departure D79.
 *
 * <h2>Nothing here retains a message</h2>
 *
 * <p>The queue is the single holding point, it is bounded, and {@link #flush} removes what it
 * encodes. There is no accessor that returns a queued item: {@link #queuedCount()} answers how many,
 * never which. That is the same shape as {@code ChatPipeline}'s own relay-only guarantee, one layer
 * out.
 *
 * <h2>A byte budget per frame, as well as a count</h2>
 *
 * <p>With a {@link Sizer}, a drain also stops before the item that would take the frame's
 * estimated encoded size past {@code maxFrameBytes}. That item is carried, whole, to the front of
 * the next frame: an item is never split and never reordered. One item alone over the budget still
 * ships, by itself, so a caller that must never send one has to shrink it before
 * {@link #enqueue} (the bridge does, for item images). The reason is the bot's tunnel: it closes the
 * socket on a frame over its payload cap, which would cost every line in the frame and the
 * connection with them. Departure D86.
 *
 * <p>The carried item counts as queued and is cleared with the queue. It sits outside the
 * drop-oldest bound, which can therefore be exceeded by exactly one.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #enqueue} is called from wherever the event arrived — a chat observer runs on the
 * platform's event thread, a session listener on {@code heimdall-io} — and must stay what it is: an
 * offer onto a lock-free queue. It must not log, must not block and must not throw.
 *
 * <p>{@link #flush} runs on {@code heimdall-sched} (an immediate drain or the one-second tick,
 * never both at once: it is a single thread), and is the only place a frame is built. It takes the
 * bus as an argument rather than reading a field, so the caller can snapshot a reference a
 * concurrent {@code disable()} might be clearing.
 *
 * @param <T> the queued item; a small immutable value, never a live handle
 */
final class FrameBatcher<T> {

    /** Turns one queued item into its wire shape. */
    interface Encoder<T> {
        Payload encode(T item);
    }

    /** An upper estimate of one item's encoded size in bytes, for the per-frame budget. */
    interface Sizer<T> {
        long estimatedBytes(T item);
    }

    private final String frameType;
    private final String arrayKey;
    private final Encoder<T> encoder;
    private final int maxQueue;
    private final int maxBatch;
    private final Sizer<T> sizer;
    private final long maxFrameBytes;

    private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<T>();
    private final AtomicInteger queued = new AtomicInteger();

    /**
     * The item a previous drain stopped before because it did not fit the byte budget. Only the
     * draining thread sets it; {@link #clear} may empty it from any thread.
     */
    private final AtomicReference<T> carried = new AtomicReference<T>();

    FrameBatcher(String frameType, String arrayKey, Encoder<T> encoder, int maxQueue, int maxBatch) {
        this(frameType, arrayKey, encoder, maxQueue, maxBatch, null, Long.MAX_VALUE);
    }

    /** As above, with a per-frame byte budget measured by {@code sizer}. */
    FrameBatcher(String frameType, String arrayKey, Encoder<T> encoder, int maxQueue, int maxBatch,
            Sizer<T> sizer, long maxFrameBytes) {
        this.frameType = frameType;
        this.arrayKey = arrayKey;
        this.encoder = encoder;
        this.maxQueue = maxQueue;
        this.maxBatch = maxBatch;
        this.sizer = sizer;
        this.maxFrameBytes = maxFrameBytes;
    }

    /**
     * Offers an item onto the queue, dropping the <em>oldest</em> if that would exceed the cap.
     *
     * <p>Oldest rather than newest, matching the console module: what a flood makes valuable is the
     * present, and a relay that started refusing new messages the moment it fell behind would go
     * permanently silent under exactly the load somebody is watching.
     */
    void enqueue(T item) {
        if (item == null) {
            return;
        }
        queue.add(item);
        // incrementAndGet first, so concurrent producers converge on the same ceiling rather than
        // each independently deciding they are the one under it.
        if (queued.incrementAndGet() > maxQueue && queue.poll() != null) {
            queued.decrementAndGet();
        }
    }

    /**
     * Drains up to {@code maxBatch} items and, if {@code bus} is connected, ships them as one frame.
     *
     * <p>Draining happens unconditionally; only the send is conditional.
     *
     * @param bus a snapshot the caller took, so a concurrent disable cannot hand this a half-torn
     *     reference. {@code null} means the module is not enabled and nothing is drained at all —
     *     which is right, because there is then no scheduled flush to bound the queue either, and
     *     {@code disable()} clears it outright
     * @return {@code true} if a frame was actually sent
     */
    boolean flush(TunnelBus bus) {
        if (bus == null || (queue.isEmpty() && carried.get() == null)) {
            return false;
        }

        List<T> batch = new ArrayList<T>(maxBatch);
        long bytes = 0L;
        for (int i = 0; i < maxBatch; i++) {
            T item = carried.getAndSet(null);
            if (item == null) {
                item = queue.poll();
                if (item == null) {
                    break;
                }
            }
            if (sizer != null) {
                long size = Math.max(0L, sizer.estimatedBytes(item));
                if (!batch.isEmpty() && bytes + size > maxFrameBytes) {
                    // Next frame, first in line: never split, never reordered. Still counted as
                    // queued, so the bridge's backlog check asks for another drain straight away.
                    carried.set(item);
                    break;
                }
                bytes += size;
            }
            queued.decrementAndGet();
            batch.add(item);
        }
        if (batch.isEmpty()) {
            return false;
        }

        // The batch is already drained at this point. Whether or not this send happens, the items
        // above are gone from the queue for good — that is the discard half of drain-and-discard.
        if (!bus.isConnected()) {
            return false;
        }

        List<Payload> encoded = new ArrayList<Payload>(batch.size());
        for (T item : batch) {
            encoded.add(encoder.encode(item));
        }
        bus.send(frameType, Payload.builder().putChildren(arrayKey, encoded).build());
        return true;
    }

    /**
     * Empties the queue. Called on enable and on disable, so a cycle never replays a stale batch.
     *
     * <p>Polls and decrements once per item rather than resetting the counter: every successful
     * {@code add} is then matched by exactly one increment and every successful {@code poll} by
     * exactly one decrement, so the count settles back to the queue's size even when an enqueue or
     * a drain overlaps the clear. A reset to zero could land between an enqueue's {@code add} and
     * its increment and leave the counter off by one for good.
     */
    void clear() {
        // The carried item is still counted as queued (flush stops before decrementing it).
        if (carried.getAndSet(null) != null) {
            queued.decrementAndGet();
        }
        while (queue.poll() != null) {
            queued.decrementAndGet();
        }
    }

    /**
     * Whether anything is queued, read from the queue itself (and the item a budget-limited drain
     * carried over) rather than the counter, which can be transiently off by one while an enqueue or
     * a clear is mid-way. The bridge's backlog re-check uses this so a queued line can never hide
     * behind a counter that has not caught up.
     */
    boolean hasQueued() {
        return carried.get() != null || !queue.isEmpty();
    }

    /**
     * How many items are currently queued.
     *
     * <p>A count and nothing else. There is deliberately no accessor that hands one back — see the
     * class javadoc.
     */
    int queuedCount() {
        return queued.get();
    }
}
