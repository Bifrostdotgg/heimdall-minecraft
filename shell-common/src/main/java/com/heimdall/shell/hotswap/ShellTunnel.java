package com.heimdall.shell.hotswap;

import com.heimdall.api.HeimdallTunnel;
import com.heimdall.core.json.Payload;
import com.heimdall.core.util.Registration;
import com.heimdall.shell.contract.TunnelBackend;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@link HeimdallTunnel} other plugins hold: permanent, and forwarding to whichever core is
 * running.
 *
 * <p>Installed once, at shell enable, into {@code HeimdallTunnelProvider} and (on Bukkit) the
 * {@code ServicesManager}, and never replaced, so a plugin that cached it keeps a working reference
 * across every swap. The subscriptions made through {@link #on} live here too, so they survive a
 * swap with no action from the plugin that made them. See {@link TunnelBackend} for why.
 *
 * <p>Replies to an inbound request go through whichever backend is bound <em>when the plugin
 * answers</em>, not the one that delivered the request, so a handler never calls into a core that
 * has stopped. A reply that crosses a swap is still lost (the bot's correlation was on the old
 * connection), which is the same outcome as a reply that crosses a reconnect.
 */
public final class ShellTunnel implements HeimdallTunnel {

    /** How a reply type is derived from the request type, matching v2. */
    private static final String RESULT_SUFFIX = ".result";

    private final ShellLog log;
    private final String shellVersion;
    private final AtomicReference<TunnelBackend> backend = new AtomicReference<TunnelBackend>();
    private final Map<String, InboundHandler> handlers =
            new ConcurrentHashMap<String, InboundHandler>();

    public ShellTunnel(ShellLog log, String shellVersion) {
        this.log = log;
        this.shellVersion = shellVersion;
    }

    /** Makes {@code next} the backend; closing the handle clears it if it is still the one bound. */
    public Registration bind(final TunnelBackend next) {
        if (next == null) {
            return Registration.NONE;
        }
        backend.set(next);
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                backend.compareAndSet(next, null);
            }
        });
    }

    /** Clears the backend if its class belongs to {@code retired}; answers whether it did. */
    boolean sweep(LoadedCore retired) {
        TunnelBackend current = backend.get();
        return current != null && retired.owns(current.getClass())
                && backend.compareAndSet(current, null);
    }

    /** Whether a core has bound a backend. */
    boolean hasBackend() {
        return backend.get() != null;
    }

    /**
     * Hands an unclaimed inbound message to the plugin subscribed to its type.
     *
     * @return whether a subscriber took it
     */
    public boolean deliver(final String requestId, final String type, Payload payload) {
        InboundHandler handler = type == null ? null : handlers.get(type);
        if (handler == null) {
            return false;
        }
        try {
            handler.handle(payload == null ? Payload.empty() : payload, new Responder() {
                @Override
                public void respond(Payload reply) {
                    TunnelBackend current = backend.get();
                    if (current == null) {
                        log.debug("dropping a reply to '" + type + "': no core is connected");
                        return;
                    }
                    current.reply(requestId, type + RESULT_SUFFIX,
                            reply == null ? Payload.empty() : reply);
                }
            });
        } catch (RuntimeException broken) {
            // One misbehaving consumer must not take the core's dispatch loop with it. The bot's
            // request times out, which is the honest outcome.
            log.error("a HeimdallTunnel consumer threw handling '" + type + "'", broken);
        }
        return true;
    }

    @Override
    public String version() {
        TunnelBackend current = backend.get();
        return current == null ? shellVersion : current.version();
    }

    @Override
    public boolean isConnected() {
        TunnelBackend current = backend.get();
        return current != null && current.isConnected();
    }

    @Override
    public void publish(String type, Payload payload) {
        TunnelBackend current = backend.get();
        if (current != null) {
            current.publish(type, payload == null ? Payload.empty() : payload);
        }
    }

    @Override
    public CompletableFuture<Payload> request(String type, Payload payload, long timeoutMs) {
        TunnelBackend current = backend.get();
        if (current == null) {
            CompletableFuture<Payload> refused = new CompletableFuture<Payload>();
            refused.completeExceptionally(
                    new IllegalStateException("tunnel is not connected (Heimdall is updating)"));
            return refused;
        }
        return current.request(type, payload == null ? Payload.empty() : payload, timeoutMs);
    }

    @Override
    public Registration on(final String type, InboundHandler handler) {
        if (type == null || type.isEmpty() || handler == null) {
            return Registration.NONE;
        }
        final InboundHandler registered = handler;
        InboundHandler previous = handlers.put(type, handler);
        if (previous != null) {
            log.warn("a second plugin subscribed to inbound '" + type + "'; the earlier handler "
                    + "has been replaced");
        }
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                // Remove only if it is still ours: a later subscriber has taken over otherwise.
                handlers.remove(type, registered);
            }
        });
    }
}
