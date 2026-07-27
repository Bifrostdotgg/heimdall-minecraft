package com.heimdall.api;

import com.heimdall.core.json.Payload;
import com.heimdall.core.util.Registration;
import java.util.concurrent.CompletableFuture;

/**
 * The public SPI other server plugins use to ride Heimdall's connection to the bot.
 *
 * <p>Heimdall keeps exactly one outbound WebSocket to the dashboard, authenticated, reconnecting
 * and heartbeated. A plugin that wants to push an event to Discord, ask the bot a question, or be
 * told when the dashboard wants something can use that socket instead of opening — and securing,
 * and reconnecting — its own. Trace is the first consumer; the shape is deliberately general.
 *
 * <h2>Getting hold of one</h2>
 *
 * <p>On the Bukkit family it is registered with the {@code ServicesManager}:
 *
 * <pre>{@code
 * RegisteredServiceProvider<HeimdallTunnel> reg =
 *         Bukkit.getServicesManager().getRegistration(HeimdallTunnel.class);
 * HeimdallTunnel tunnel = reg == null ? null : reg.getProvider();
 * }</pre>
 *
 * <p>Velocity has no equivalent registry, so {@link HeimdallTunnelProvider#get()} is the portable
 * way to ask on either platform. A plugin that only soft-depends on Heimdall should look this
 * interface up reflectively so it keeps loading when Heimdall is not installed.
 *
 * <h2>What changed from v2</h2>
 *
 * <p>The payload type is {@link Payload} rather than Gson's {@code JsonObject}. v3 relocates Gson
 * into {@code com.heimdall.libs.gson}, so a v2-shaped signature would have handed callers a type
 * whose package changes whenever the shading does — and the caller's own unrelocated Gson would not
 * be assignable to it. {@link Payload} is Heimdall's own, ships unrelocated, and is the same type
 * core uses internally.
 *
 * <p>{@link #on} returns a {@link Registration} instead of nothing, so a plugin being disabled can
 * unsubscribe rather than leaving a handler bound to a classloader that is going away.
 *
 * <h2>Threading</h2>
 *
 * <p>Every method is safe from any thread. Handlers are invoked on Heimdall's IO pool, never on the
 * socket's reading thread and never on a server thread — so a handler that needs the Bukkit API
 * must hop to the main thread itself.
 *
 * <p>Methods are added to this interface over time. None is ever removed or re-signatured.
 */
public interface HeimdallTunnel {

    /**
     * The running plugin version, e.g. {@code 3.0.0}.
     *
     * @return the plugin version string, never {@code null}
     */
    String version();

    /**
     * Whether the socket is open right now.
     *
     * <p>Worth checking before {@link #request}, which fails immediately when it is not, but not
     * worth checking before {@link #publish} — see that method's note on why a queue would be worse
     * than a drop.
     */
    boolean isConnected();

    /**
     * Fire-and-forget an event to the bot.
     *
     * <p><strong>Silently does nothing while the tunnel is down.</strong> That is deliberate and it
     * is not a swallowed error: the bot is the source of truth and clients re-sync on reconnect, so
     * a queue of frames waiting for a bot that has been redeploying for a minute would deliver a
     * burst of stale events the moment it came back.
     *
     * @param type the message type; the bot routes on this
     * @param payload the body; {@code null} is treated as empty
     */
    void publish(String type, Payload payload);

    /**
     * Sends a request and completes when the bot's correlated reply arrives.
     *
     * <p>Fails with {@link java.util.concurrent.TimeoutException} after {@code timeoutMs}, fails
     * immediately if the tunnel is not connected, and fails — rather than hanging — on any
     * disconnect while it is outstanding.
     *
     * <p>The future completes on Heimdall's IO pool. Do not block on it from a {@link #on} handler.
     *
     * @param timeoutMs deadline in milliseconds; values below 1 are treated as 1
     */
    CompletableFuture<Payload> request(String type, Payload payload, long timeoutMs);

    /**
     * Subscribes to a dashboard-to-server message type.
     *
     * <p>The handler is given the payload and a responder. Calling the responder sends
     * <code>&lt;type&gt;.result</code> back <em>correlated to the request</em> — the bot has a
     * future waiting on that id, so answering through any other route produces a reply it files as
     * unsolicited and a request that times out.
     *
     * <p>One handler per type: subscribing a type that is already taken replaces the previous
     * handler, which is v2's behaviour. Only types Heimdall does not handle itself reach here.
     *
     * @return a handle that unsubscribes; closing it twice is a no-op
     */
    Registration on(String type, InboundHandler handler);

    /** Handles one inbound message type. */
    interface InboundHandler {

        /**
         * @param payload the message body, never {@code null}
         * @param responder answers the request; calling it more than once sends more than one reply
         */
        void handle(Payload payload, Responder responder);
    }

    /** Answers one request, correlated to it. */
    interface Responder {

        /** Sends <code>&lt;type&gt;.result</code> with this body. {@code null} sends an empty one. */
        void respond(Payload payload);
    }
}
