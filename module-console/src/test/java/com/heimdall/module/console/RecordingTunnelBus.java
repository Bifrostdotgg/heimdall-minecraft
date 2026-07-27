package com.heimdall.module.console;

import com.heimdall.core.json.Payload;
import com.heimdall.core.tunnel.ProtocolMode;
import com.heimdall.core.tunnel.TunnelBus;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

/**
 * A {@link TunnelBus} test double that records every {@link #send} and lets a test steer whether it
 * is "connected".
 *
 * <p>No shared fixture for this existed anywhere in {@code core}'s test fixtures at the time this
 * was written (checked: only {@code OfflineTunnelBus}, which is always disconnected, and
 * {@code TunnelClient}, the real socket-backed implementation — neither is steerable per-test). This
 * module's tests are what need one: they have to prove a batch is dropped while disconnected and
 * shipped once reconnected, which requires flipping connectivity mid-test on the same instance.
 *
 * <p>Only {@link #send} and {@link #isConnected} are exercised by {@link HeimdallConsoleModule} —
 * it never subscribes, replies or waits on a correlated response — so the rest of the interface is
 * implemented just enough to satisfy the contract, not to be a general-purpose fake.
 */
final class RecordingTunnelBus implements TunnelBus {

    /** One recorded {@link #send} call. */
    static final class Sent {
        final String type;
        final Payload payload;

        Sent(String type, Payload payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    private final List<Sent> sent = Collections.synchronizedList(new ArrayList<Sent>());
    private volatile boolean connected;

    RecordingTunnelBus connected(boolean value) {
        this.connected = value;
        return this;
    }

    List<Sent> sent() {
        synchronized (sent) {
            return new ArrayList<Sent>(sent);
        }
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
    public CompletableFuture<Payload> sendAndWait(String type, Payload payload, long timeoutMs) {
        CompletableFuture<Payload> failed = new CompletableFuture<Payload>();
        failed.completeExceptionally(new TimeoutException("RecordingTunnelBus never replies"));
        return failed;
    }

    @Override
    public CompletableFuture<Payload> sendAndWait(String type, Payload payload) {
        return sendAndWait(type, payload, 1L);
    }

    @Override
    public Registration subscribe(String type, TunnelMessageHandler handler) {
        return Registration.NONE;
    }

    @Override
    public Registration subscribe(String type, TunnelMessageHandler handler, Executor executor) {
        return Registration.NONE;
    }

    @Override
    public ProtocolMode mode() {
        return connected ? ProtocolMode.V3 : ProtocolMode.UNKNOWN;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }
}
