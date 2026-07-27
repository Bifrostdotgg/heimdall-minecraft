package com.heimdall.core.module;

import com.heimdall.core.json.Payload;
import com.heimdall.core.tunnel.ProtocolMode;
import com.heimdall.core.tunnel.TunnelBus;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import com.heimdall.core.util.Registration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The bus a module gets when there is no tunnel at all.
 *
 * <p>A server whose {@code bootstrap.yml} has never been filled in has nowhere to connect, and the
 * modules still have to load — otherwise the setup command that would fix it is itself unavailable.
 * So rather than handing a module {@code null} and adding a check to every call site, this behaves
 * exactly as a disconnected tunnel does: sends are silent no-ops, requests fail immediately,
 * subscriptions succeed and never fire.
 *
 * <p>Behaving like "disconnected" rather than throwing is the point. A module has to handle the
 * tunnel being down anyway — that is the ordinary case during a bot redeploy — so this needs no
 * separate code path, and a module written against it is already correct for the real one.
 */
final class OfflineTunnelBus implements TunnelBus {

    static final OfflineTunnelBus INSTANCE = new OfflineTunnelBus();

    private OfflineTunnelBus() {
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
        failed.completeExceptionally(
                new IllegalStateException("this server has no tunnel configured; cannot send '" + type + "'"));
        return failed;
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
        return ProtocolMode.UNKNOWN;
    }

    @Override
    public boolean isConnected() {
        return false;
    }
}
