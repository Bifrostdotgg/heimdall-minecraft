package com.heimdall.core.tunnel;

import com.heimdall.core.json.Payload;
import com.heimdall.core.util.Registration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The tunnel, as a feature module sees it: send, reply, ask, and subscribe.
 *
 * <p>An interface rather than the concrete {@link TunnelClient} so a module can be tested against a
 * fake bus with no socket anywhere, and so {@code ModuleManager} can hand each module a
 * <em>wrapper</em> that records the subscriptions it makes. That wrapping is what lets a module be
 * disabled cleanly even if it never closed a registration itself.
 *
 * <h2>Sending while disconnected</h2>
 *
 * <p>{@link #send} and {@link #reply} silently do nothing when the tunnel is down. That is
 * deliberate and it is not a swallowed error: the bot is the source of truth, the plugin re-syncs
 * on reconnect, and a queue of frames waiting for a bot that has been redeploying for a minute
 * would deliver a burst of stale events the moment it came back. {@link #sendAndWait} does report
 * the failure, because a caller waiting on an answer has to be told there will not be one.
 *
 * <h2>Threading</h2>
 *
 * <p>Every method here is safe from any thread. Handlers registered through {@link #subscribe} run
 * on the executor given (or {@code heimdall-io}), never on the socket's reading thread.
 */
public interface TunnelBus {

    /** Fire-and-forget, with a fresh correlation id. */
    void send(String type, Payload payload);

    /**
     * Answers a request by echoing its id back.
     *
     * <p>The echoed id <em>is</em> the correlation — the bot has a future waiting on it. Sending a
     * fresh id instead produces a reply the bot files as unsolicited and a request that times out.
     */
    void reply(String requestId, String type, Payload payload);

    /**
     * Sends a request and completes when the correlated reply arrives.
     *
     * <p>The returned future completes on the socket's reading thread, so <strong>never block on it
     * from a handler</strong> and never chain heavy work onto it without naming an executor.
     *
     * <p>Fails with {@link java.util.concurrent.TimeoutException} after {@code timeoutMs}, and
     * fails immediately if the tunnel is not connected. It also fails — rather than hanging — on
     * any disconnect, reconnect or shutdown while it is outstanding.
     *
     * @param timeoutMs deadline in milliseconds; values below 1 are treated as 1
     */
    CompletableFuture<Payload> sendAndWait(String type, Payload payload, long timeoutMs);

    /** {@link #sendAndWait} with {@link TunnelSettings#requestTimeoutMs()}. */
    CompletableFuture<Payload> sendAndWait(String type, Payload payload);

    /**
     * Subscribes to a message type, dispatching on {@code heimdall-io}.
     *
     * @return a handle that unsubscribes; closing it twice is a no-op
     */
    Registration subscribe(String type, TunnelMessageHandler handler);

    /**
     * Subscribes to a message type, dispatching on a named executor.
     *
     * <p>The overload exists for handlers that must run somewhere specific — the main server thread
     * on Bukkit, most obviously, since almost nothing in the Bukkit API may be touched from
     * anywhere else.
     */
    Registration subscribe(String type, TunnelMessageHandler handler, Executor executor);

    /** The protocol this connection negotiated. {@link ProtocolMode#UNKNOWN} while disconnected. */
    ProtocolMode mode();

    /** Whether a socket is currently open. */
    boolean isConnected();
}
