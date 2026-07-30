package com.heimdall.module.bridge;

import com.heimdall.core.json.Payload;
import com.heimdall.core.tunnel.TunnelBus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A bounded queue that ships what it holds as one frame a second, and throws away what it cannot.
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
 * <h2>Threading</h2>
 *
 * <p>{@link #enqueue} is called from wherever the event arrived — a chat observer runs on the
 * platform's event thread, a session listener on {@code heimdall-io} — and must stay what it is: an
 * offer onto a lock-free queue. It must not log, must not block and must not throw.
 *
 * <p>{@link #flush} runs on {@code heimdall-sched}, once a second, and is the only place a frame is
 * built. It takes the bus as an argument rather than reading a field, so the caller can snapshot a
 * reference a concurrent {@code disable()} might be clearing.
 *
 * @param <T> the queued item; a small immutable value, never a live handle
 */
final class FrameBatcher<T> {

    /** Turns one queued item into its wire shape. */
    interface Encoder<T> {
        Payload encode(T item);
    }

    private final String frameType;
    private final String arrayKey;
    private final Encoder<T> encoder;
    private final int maxQueue;
    private final int maxBatch;

    private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<T>();
    private final AtomicInteger queued = new AtomicInteger();

    FrameBatcher(String frameType, String arrayKey, Encoder<T> encoder, int maxQueue, int maxBatch) {
        this.frameType = frameType;
        this.arrayKey = arrayKey;
        this.encoder = encoder;
        this.maxQueue = maxQueue;
        this.maxBatch = maxBatch;
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
        if (bus == null || queue.isEmpty()) {
            return false;
        }

        List<T> batch = new ArrayList<T>(maxBatch);
        for (int i = 0; i < maxBatch; i++) {
            T item = queue.poll();
            if (item == null) {
                break;
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

    /** Empties the queue. Called on enable and on disable, so a cycle never replays a stale batch. */
    void clear() {
        queue.clear();
        queued.set(0);
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
