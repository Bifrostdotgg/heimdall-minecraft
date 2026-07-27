package com.heimdall.core.testing;

import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.tunnel.ProtocolMode;
import com.heimdall.core.tunnel.TunnelBus;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * A tunnel that records what was sent and lets a test push what arrives.
 *
 * <p>Lives in core's fixtures rather than in a module's test sources because every feature module
 * needs exactly this: whitelist reports connection attempts over it, rolesync subscribes to
 * {@code role_sync}, console ships {@code console_line} batches. Three copies would be three
 * slightly different answers to "is this bus connected", which is the question most of those tests
 * turn on.
 *
 * <p>Handlers run <strong>inline</strong> on {@link #push}, unlike the real bus, which runs them on
 * {@code heimdall-io} (departure D27). That is deliberate: a test asserting what a handler did
 * should not need a latch, and the off-thread dispatch is core's property, pinned in core's own
 * tests rather than re-proved by every module.
 *
 * <p>Thread-safe.
 */
public final class RecordingTunnelBus implements TunnelBus {

    /** One frame the code under test sent. */
    public static final class Sent {

        private final String type;
        private final Payload payload;

        Sent(String type, Payload payload) {
            this.type = type;
            this.payload = payload == null ? Payload.empty() : payload;
        }

        public String type() {
            return type;
        }

        public Payload payload() {
            return payload;
        }

        @Override
        public String toString() {
            return "Sent{" + type + " " + payload + "}";
        }
    }

    private final CopyOnWriteArrayList<Sent> sent = new CopyOnWriteArrayList<Sent>();
    private final Map<String, CopyOnWriteArrayList<TunnelMessageHandler>> handlers =
            Collections.synchronizedMap(
                    new LinkedHashMap<String, CopyOnWriteArrayList<TunnelMessageHandler>>());

    private volatile boolean connected = true;
    private volatile ProtocolMode mode = ProtocolMode.V3;

    /** Everything sent, oldest first. */
    public List<Sent> sent() {
        return Collections.unmodifiableList(new ArrayList<Sent>(sent));
    }

    /** Only the frames of one type, oldest first. */
    public List<Sent> sent(String type) {
        List<Sent> matching = new ArrayList<Sent>();
        for (Sent frame : sent) {
            if (frame.type().equals(type)) {
                matching.add(frame);
            }
        }
        return Collections.unmodifiableList(matching);
    }

    /** Forgets what was sent, so one test can assert about two phases in turn. */
    public void clearSent() {
        sent.clear();
    }

    /** How many handlers are subscribed to a type — the leak assertion for a module toggle. */
    public int subscriberCount(String type) {
        CopyOnWriteArrayList<TunnelMessageHandler> forType = handlers.get(type);
        return forType == null ? 0 : forType.size();
    }

    /** Delivers a frame to whoever subscribed, inline. Returns how many handlers saw it. */
    public int push(String type, Payload payload) {
        return push(Envelope.fresh(type, payload));
    }

    /** Delivers a whole envelope, so a test can control the correlation id. */
    public int push(Envelope envelope) {
        CopyOnWriteArrayList<TunnelMessageHandler> forType = handlers.get(envelope.type());
        if (forType == null) {
            return 0;
        }
        int delivered = 0;
        for (TunnelMessageHandler handler : forType) {
            handler.onMessage(envelope);
            delivered++;
        }
        return delivered;
    }

    /** Sets whether the link is up. What the console module's drain-and-discard branches on. */
    public RecordingTunnelBus connected(boolean value) {
        this.connected = value;
        return this;
    }

    /** Says the link is down. */
    public RecordingTunnelBus disconnected() {
        return connected(false);
    }

    /** Says the link is up again. */
    public RecordingTunnelBus reconnected() {
        return connected(true);
    }

    public RecordingTunnelBus mode(ProtocolMode value) {
        this.mode = value;
        return this;
    }

    @Override
    public void send(String type, Payload payload) {
        sent.add(new Sent(type, payload));
    }

    @Override
    public void reply(String requestId, String type, Payload payload) {
        sent.add(new Sent(type, payload));
    }

    @Override
    public CompletableFuture<Payload> sendAndWait(String type, Payload payload) {
        sent.add(new Sent(type, payload));
        return new CompletableFuture<Payload>();
    }

    @Override
    public CompletableFuture<Payload> sendAndWait(String type, Payload payload, long timeoutMs) {
        return sendAndWait(type, payload);
    }

    @Override
    public Registration subscribe(String type, TunnelMessageHandler handler) {
        return subscribe(type, handler, null);
    }

    @Override
    public Registration subscribe(
            final String type, final TunnelMessageHandler handler, Executor executor) {
        final CopyOnWriteArrayList<TunnelMessageHandler> forType;
        synchronized (handlers) {
            CopyOnWriteArrayList<TunnelMessageHandler> existing = handlers.get(type);
            if (existing == null) {
                existing = new CopyOnWriteArrayList<TunnelMessageHandler>();
                handlers.put(type, existing);
            }
            forType = existing;
        }
        forType.add(handler);
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                forType.remove(handler);
            }
        });
    }

    @Override
    public ProtocolMode mode() {
        return mode;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }
}
