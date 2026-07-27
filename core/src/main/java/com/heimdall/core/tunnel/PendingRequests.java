package com.heimdall.core.tunnel;

import com.heimdall.core.json.Payload;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Correlation ids awaiting their replies.
 *
 * <p><strong>Every future here is guaranteed to complete.</strong> That is the whole contract, and
 * it is the one thing this class exists to make true. A future that hangs is worse than one that
 * fails: the caller is a command handler or a login path holding a thread, and "the bot never
 * answered" has to become a visible timeout rather than a permanently stuck request. So there are
 * exactly three ways out — the reply arrives, the deadline elapses, or the tunnel is torn down —
 * and {@link #failAll} covers every teardown path in {@link TunnelClient}: disconnect, reconnect
 * and shutdown alike.
 *
 * <p>Thread-safe. Registrations come from caller threads, completions from the socket's reading
 * thread, timeouts from {@code heimdall-ws}, and {@link #failAll} from any of them.
 */
final class PendingRequests {

    private final ScheduledExecutorService scheduler;
    private final Map<String, CompletableFuture<Payload>> pending =
            new ConcurrentHashMap<String, CompletableFuture<Payload>>();

    PendingRequests(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Registers a request and arms its deadline.
     *
     * @param id the correlation id the reply must echo
     * @param type only used in the timeout message; a bare "request timed out" in a server log is
     *     useless when four kinds of request share this map
     */
    CompletableFuture<Payload> register(final String id, final String type, long timeoutMs) {
        CompletableFuture<Payload> future = new CompletableFuture<Payload>();
        pending.put(id, future);
        try {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    CompletableFuture<Payload> stale = pending.remove(id);
                    if (stale != null) {
                        stale.completeExceptionally(
                                new TimeoutException("tunnel request timed out: " + type));
                    }
                }
            }, Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // The scheduler is already shutting down, so nothing will ever time this out. Failing
            // it here and now is the only way to keep the never-hangs promise.
            pending.remove(id);
            future.completeExceptionally(
                    new IllegalStateException("tunnel is shutting down; request not sent: " + type));
        }
        return future;
    }

    /**
     * Completes the future waiting on this id, if any.
     *
     * @return whether an id was actually waiting — which is how the dispatcher tells a reply from
     *     an unsolicited message
     */
    boolean complete(String id, Payload payload) {
        CompletableFuture<Payload> future = pending.remove(id);
        if (future == null) {
            return false;
        }
        future.complete(payload == null ? Payload.empty() : payload);
        return true;
    }

    /** Drops a registration without completing it. Used when the send itself could not happen. */
    CompletableFuture<Payload> forget(String id) {
        return pending.remove(id);
    }

    /**
     * Fails every outstanding request with {@code reason}, and empties the map.
     *
     * <p>Called on <em>every</em> teardown, including the in-place reconnect. A request outstanding
     * across a reconnect can never be answered — the correlation id lived on the old socket and the
     * bot has forgotten it — so leaving it to time out means a caller blocked for the full deadline
     * on an answer that was already impossible.
     */
    void failAll(String reason) {
        for (Map.Entry<String, CompletableFuture<Payload>> entry : pending.entrySet()) {
            entry.getValue().completeExceptionally(new IllegalStateException(reason));
        }
        pending.clear();
    }

    /** How many requests are outstanding. For tests and diagnostics. */
    int size() {
        return pending.size();
    }
}
