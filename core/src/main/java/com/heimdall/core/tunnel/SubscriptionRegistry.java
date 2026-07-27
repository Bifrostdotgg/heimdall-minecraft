package com.heimdall.core.tunnel;

import com.heimdall.core.json.Envelope;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.Registration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/**
 * Message type → handlers, with each handler's own executor.
 *
 * <p><strong>Dispatch never runs a handler on the socket's reading thread.</strong> That is the one
 * rule this class exists to enforce. v2 called its single message handler inline from the read
 * callback, which meant a handler that hit the API — role sync did — stopped the socket reading for
 * the length of an HTTP round trip. The heartbeat check would then see no traffic and abort a
 * connection that was working perfectly.
 *
 * <p>{@link CopyOnWriteArrayList} per type rather than a lock: subscriptions are made at module
 * enable time and read on every inbound frame, so the read path should not synchronise. Iterating a
 * snapshot also means a handler that unsubscribes itself mid-dispatch cannot break the loop.
 *
 * <p>Thread-safe throughout.
 */
final class SubscriptionRegistry {

    private final HeimdallLogger logger;
    private final Map<String, CopyOnWriteArrayList<Subscription>> byType =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<Subscription>>();

    SubscriptionRegistry(HeimdallLogger logger) {
        this.logger = logger;
    }

    /**
     * Registers a handler.
     *
     * @return a handle that removes exactly this registration — not every handler for the type, and
     *     not "the first one that looks equal". Two modules subscribing the same lambda to the same
     *     type stay independent.
     */
    Registration subscribe(final String type, TunnelMessageHandler handler, Executor executor) {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("type is required");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler is required");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor is required");
        }
        final Subscription subscription = new Subscription(handler, executor);
        add(type, subscription);
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                remove(type, subscription);
            }
        });
    }

    /**
     * Hands the frame to every subscriber of its type.
     *
     * @return whether anybody was listening. The caller uses this to decide whether the message is
     *     genuinely unhandled and should go to the fallback hook.
     */
    boolean dispatch(final Envelope envelope) {
        List<Subscription> handlers = byType.get(envelope.type());
        if (handlers == null || handlers.isEmpty()) {
            return false;
        }
        boolean delivered = false;
        for (final Subscription subscription : handlers) {
            try {
                subscription.executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        // Re-checked here, not only when the snapshot was taken. Dispatch reads a
                        // copy-on-write snapshot and then hands the work to an executor, so there is
                        // a window — however small — in which a registration is closed while its
                        // task is already queued. Without this a module that has been disabled can
                        // still have a handler run, which is exactly the "disabled module still
                        // reacting to events" failure the registration design exists to prevent.
                        if (!subscription.active.get()) {
                            return;
                        }
                        try {
                            subscription.handler.onMessage(envelope);
                        } catch (RuntimeException e) {
                            // Contained on purpose: one module's bad frame must not stop the other
                            // subscribers, and must not surface as an uncaught exception on a
                            // shared pool thread where it would look like the pool's fault.
                            logger.error("tunnel handler for '" + envelope.type() + "' threw", e);
                        }
                    }
                });
                delivered = true;
            } catch (RejectedExecutionException e) {
                // The pool is shutting down. Expected during teardown, and reporting it as an
                // unhandled message would be worse than saying nothing.
                logger.debug("dropped '" + envelope.type() + "': executor is shutting down");
                delivered = true;
            }
        }
        return delivered;
    }

    /** Whether anything at all is subscribed to a type. */
    boolean hasSubscribers(String type) {
        List<Subscription> handlers = byType.get(type);
        return handlers != null && !handlers.isEmpty();
    }

    /** Drops every subscription. Used only on shutdown. */
    void clear() {
        for (CopyOnWriteArrayList<Subscription> handlers : byType.values()) {
            for (Subscription subscription : handlers) {
                subscription.active.set(false);
            }
        }
        byType.clear();
    }

    /**
     * Adds a subscription under the map's bin lock.
     *
     * <p>Both this and {@link #remove} mutate the list <em>inside</em> a {@code compute}, rather
     * than fetching the list and mutating it afterwards. The obvious version —
     * {@code computeIfAbsent(type, …).add(sub)} — loses a subscription whenever the last existing
     * handler for the same type unsubscribes in the window between the fetch and the add: the
     * remover sees an empty list, drops it from the map, and the new subscription is left in a list
     * nothing can reach. Rare, silent, and it presents as a module that simply never receives its
     * messages after a config reload.
     */
    private void add(String type, final Subscription subscription) {
        byType.compute(type, new BiFunction<String,
                CopyOnWriteArrayList<Subscription>, CopyOnWriteArrayList<Subscription>>() {
            @Override
            public CopyOnWriteArrayList<Subscription> apply(
                    String key, CopyOnWriteArrayList<Subscription> handlers) {
                CopyOnWriteArrayList<Subscription> list =
                        handlers == null ? new CopyOnWriteArrayList<Subscription>() : handlers;
                list.add(subscription);
                return list;
            }
        });
    }

    /**
     * Removes one subscription, and the type's list with it if that was the last one.
     *
     * <p>The empty list is dropped rather than left behind so a module that subscribes and
     * unsubscribes on every config change does not grow the map forever.
     */
    private void remove(String type, final Subscription subscription) {
        byType.computeIfPresent(type, new BiFunction<String,
                CopyOnWriteArrayList<Subscription>, CopyOnWriteArrayList<Subscription>>() {
            @Override
            public CopyOnWriteArrayList<Subscription> apply(
                    String key, CopyOnWriteArrayList<Subscription> handlers) {
                // Deactivate first, then unlist. A task already queued on an executor checks this
                // flag before running, so the order means a handler can never run after its
                // registration was closed — only that it may be dropped slightly before.
                subscription.active.set(false);
                handlers.remove(subscription);
                return handlers.isEmpty() ? null : handlers;
            }
        });
    }

    /**
     * One handler, the executor it was registered with, and whether it is still live.
     *
     * <p>Identity-compared, so two modules registering the same lambda stay independent.
     *
     * <p>The {@code active} flag is what closes the gap between "removed from the list" and "not
     * going to run": removal only affects future dispatches, and a task handed to an executor a
     * microsecond earlier is already beyond the list's reach.
     */
    private static final class Subscription {

        private final TunnelMessageHandler handler;
        private final Executor executor;
        private final AtomicBoolean active = new AtomicBoolean(true);

        Subscription(TunnelMessageHandler handler, Executor executor) {
            this.handler = handler;
            this.executor = executor;
        }
    }
}
