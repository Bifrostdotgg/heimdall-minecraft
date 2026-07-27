package com.heimdall.module.rolesync;

import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.tunnel.ProtocolMode;
import com.heimdall.core.tunnel.TunnelBus;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import com.heimdall.core.util.Registration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * A tunnel with no socket: subscriptions can be counted, and a frame can be pushed on demand.
 *
 * <p>Counting is the point. "Enabling twice leaves one subscription" and "disabling leaves none" are
 * the assertions that catch a module leaking a listener, and they need a bus that will say how many
 * there are — which the real {@code TunnelClient} deliberately will not.
 *
 * <p><strong>Dispatch is inline, on the pushing thread.</strong> The real client hands every handler
 * to the executor it was subscribed with — {@code heimdall-io} for the no-executor overload, as
 * {@code TunnelClient.subscribe} shows — and that guarantee is pinned by core's own tests rather than
 * re-asserted here. What these tests need is determinism: a push that has finished being handled by
 * the time it returns, so an assertion needs no latch.
 *
 * <p>Thread-safe, for symmetry with the real thing.
 */
final class RecordingTunnelBus implements TunnelBus {

    private final Map<String, CopyOnWriteArrayList<TunnelMessageHandler>> byType =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<TunnelMessageHandler>>();

    /** How many handlers are subscribed to a type. The leak assertion. */
    int subscriberCount(String type) {
        List<TunnelMessageHandler> handlers = byType.get(type);
        return handlers == null ? 0 : handlers.size();
    }

    /**
     * Delivers a frame to every subscriber of its type.
     *
     * @return how many handlers saw it
     */
    int push(String type, Payload payload) {
        List<TunnelMessageHandler> handlers = byType.get(type);
        if (handlers == null) {
            return 0;
        }
        int delivered = 0;
        Envelope envelope = Envelope.fresh(type, payload);
        for (TunnelMessageHandler handler : handlers) {
            handler.onMessage(envelope);
            delivered++;
        }
        return delivered;
    }

    @Override
    public void send(String type, Payload payload) {
    }

    @Override
    public void reply(String requestId, String type, Payload payload) {
    }

    @Override
    public CompletableFuture<Payload> sendAndWait(String type, Payload payload) {
        return sendAndWait(type, payload, 0L);
    }

    @Override
    public CompletableFuture<Payload> sendAndWait(String type, Payload payload, long timeoutMs) {
        CompletableFuture<Payload> failed = new CompletableFuture<Payload>();
        failed.completeExceptionally(new IllegalStateException("no tunnel in this test"));
        return failed;
    }

    @Override
    public Registration subscribe(String type, TunnelMessageHandler handler) {
        return subscribe(type, handler, null);
    }

    @Override
    public Registration subscribe(final String type, final TunnelMessageHandler handler, Executor executor) {
        CopyOnWriteArrayList<TunnelMessageHandler> handlers = byType.get(type);
        if (handlers == null) {
            handlers = new CopyOnWriteArrayList<TunnelMessageHandler>();
            CopyOnWriteArrayList<TunnelMessageHandler> raced = byType.putIfAbsent(type, handlers);
            if (raced != null) {
                handlers = raced;
            }
        }
        final CopyOnWriteArrayList<TunnelMessageHandler> target = handlers;
        target.add(handler);
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                target.remove(handler);
            }
        });
    }

    @Override
    public ProtocolMode mode() {
        return ProtocolMode.V3;
    }

    @Override
    public boolean isConnected() {
        return true;
    }
}
