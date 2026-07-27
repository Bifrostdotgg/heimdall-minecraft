package com.heimdall.core.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Collapses concurrent requests for the same key onto one outstanding operation.
 *
 * <p>The problem this solves is duplicate work, not stale data, and the difference matters.
 * <strong>This deliberately replaces v2's 30-second response cache, which is not ported.</strong>
 * That cache kept whole {@code WhitelistResponse} objects — including the {@code roleSync} block —
 * and replayed them to later joins, so a player whose groups had just changed was handed the old
 * snapshot and had them reverted. That was the 2.4.0 outage. Here nothing is ever retained after
 * the operation completes: a second caller arriving <em>while</em> a request is in flight shares
 * its result, and a caller arriving one millisecond after it finishes makes a fresh request. There
 * is no window in which a stale answer can be served, because there is no stored answer.
 *
 * <p><strong>Nothing wires this yet.</strong> It lands in phase 1a with the rest of the plumbing;
 * the whitelist module adopts it in 1d, where the shape is a login path seeing the same UUID three
 * times in two seconds (a client retrying a connect) and a whitelist poll overlapping the previous
 * one. It is here now so that the replacement for v2's response cache exists and is tested before
 * anything is tempted to reintroduce a cache instead.
 *
 * <h2>The completion obligation</h2>
 *
 * <p><strong>The operation's future must always complete.</strong> A future that never does leaves
 * its key occupied forever: every later caller joins a result that will not arrive, and the only
 * cure is a restart. That is not hypothetical — it is what a task silently dropped by a shut-down
 * or saturated executor looks like from here.
 *
 * <p>So the {@code start} supplier must return a future from something that completes on every
 * path, including rejection. {@code CompletableFuture.supplyAsync(work, executor)} does:
 * a {@code RejectedExecutionException} completes the returned future exceptionally rather than
 * vanishing. A hand-rolled {@code executor.execute(() -> future.complete(...))} does not, because
 * the rejection is thrown at the caller and the future is left pending.
 *
 * <p>Thread-safe. The returned future completes on whatever thread completed the underlying
 * operation.
 *
 * <p><strong>Do not complete or cancel the returned future.</strong> Every caller for a key holds
 * the same instance, so cancelling it would cancel it for everyone. Chain off it instead.
 *
 * @param <K> the collapse key — anything with sane {@code equals}/{@code hashCode}, usually a UUID
 *     string
 * @param <V> the result type
 */
public final class InFlight<K, V> {

    private final ConcurrentHashMap<K, CompletableFuture<V>> outstanding =
            new ConcurrentHashMap<K, CompletableFuture<V>>();

    /**
     * Returns the outstanding operation for {@code key}, starting one if there is none.
     *
     * <p>{@code start} is invoked at most once per collapse group, on the calling thread. It is
     * expected to return promptly with a future — it should dispatch onto an executor, not block.
     *
     * @param key what to collapse on
     * @param start starts the operation; called only by the caller that wins the race
     * @return a future completing with the operation's result
     */
    public CompletableFuture<V> submit(K key, Supplier<CompletableFuture<V>> start) {
        if (key == null || start == null) {
            throw new IllegalArgumentException("key and start are both required");
        }

        // A shared placeholder rather than the operation's own future: the entry has to be
        // published BEFORE the operation starts, or two callers racing here would both start one.
        CompletableFuture<V> placeholder = new CompletableFuture<V>();
        CompletableFuture<V> existing = outstanding.putIfAbsent(key, placeholder);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<V> operation;
        try {
            operation = start.get();
        } catch (RuntimeException e) {
            outstanding.remove(key, placeholder);
            placeholder.completeExceptionally(e);
            return placeholder;
        }
        if (operation == null) {
            outstanding.remove(key, placeholder);
            placeholder.completeExceptionally(new IllegalStateException("start returned no future"));
            return placeholder;
        }

        // whenComplete, not whenCompleteAsync: the continuation is a map removal and a completion,
        // so running it inline on the completing thread is both cheapest and the only form that
        // needs no executor — which is what the conformance rules require of the async overloads.
        operation.whenComplete(new BiConsumer<V, Throwable>() {
            @Override
            public void accept(V value, Throwable failure) {
                // Removed before completing, so a listener that immediately resubmits the same key
                // starts a fresh operation rather than joining one that has already finished.
                outstanding.remove(key, placeholder);
                if (failure != null) {
                    placeholder.completeExceptionally(failure);
                } else {
                    placeholder.complete(value);
                }
            }
        });
        return placeholder;
    }

    /** How many operations are outstanding. Diagnostics only. */
    public int size() {
        return outstanding.size();
    }

    /** Whether an operation is outstanding for {@code key}. */
    public boolean isInFlight(K key) {
        return key != null && outstanding.containsKey(key);
    }
}
