package com.heimdall.core.tunnel;

/**
 * One WebSocket connection, reduced to the five things {@link TunnelClient} actually does with it.
 *
 * <p><strong>This interface exists so the WebSocket library stays swappable.</strong> The choice of
 * nv-websocket-client is driven by a constraint that has nothing to do with its API — it is the
 * only mature client with no logging facade, and legacy Spigot ships no slf4j, so the obvious pick
 * (Java-WebSocket) is a guaranteed {@code NoClassDefFoundError} on every server older than 1.16.
 * That constraint could change; the reconnect, heartbeat and correlation logic built on top of it
 * should not have to. So {@code TunnelClient} names this type and never the library's, and the
 * adapter that bridges the two is one small class.
 *
 * <p>It also makes the interesting tests possible. Heartbeat timeout, the reconnect CAS, backoff
 * doubling and pending-future failure are all about what the client does when a socket misbehaves,
 * and a fake socket can misbehave on demand in a way a real server cannot.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>A socket is single-use. {@link #connect()} is called exactly once; after {@link #abort()} or
 * {@link #close} the instance is dead and a reconnect asks the factory for a new one.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #sendText}, {@link #abort} and {@link #close} may be called from any thread and must
 * not block waiting on the peer. Listener callbacks arrive on the implementation's own reading
 * thread — never on a Heimdall pool — which is why {@link TunnelClient} does almost nothing in them
 * beyond dispatching elsewhere.
 */
public interface TunnelSocket {

    /**
     * Starts connecting, without blocking.
     *
     * <p>Success and failure both arrive on the listener ({@code onOpen} or {@code onError}); this
     * method throwing means the attempt could not even be started, which the client treats as an
     * ordinary connect failure.
     */
    void connect();

    /**
     * Queues a text frame.
     *
     * <p>Silently does nothing when the socket is not open. Callers on the login path must not be
     * made to check first, and a message that could not be sent because the tunnel is down is an
     * ordinary condition here, not an error — the bot is the source of truth and the plugin
     * re-syncs on reconnect.
     */
    void sendText(String text);

    /**
     * Drops the connection <strong>without</strong> waiting for a close handshake.
     *
     * <p>This is the one that recovers a wedged link, and the difference is not cosmetic. A
     * graceful close only completes when the peer answers with its own close frame; a peer that has
     * been black-holed by a firewall or a dead NAT entry never will, so a client that closes
     * gracefully on a heartbeat timeout waits forever for a reply that is not coming and never
     * reconnects. v2 shipped that bug and the comment explaining the fix is still in its source.
     */
    void abort();

    /**
     * Closes gracefully, sending a close frame with the given code and reason.
     *
     * <p>For the cases where the peer is known to be alive and deserves to be told: the plugin
     * shutting down, or the tunnel being switched off by config.
     */
    void close(int code, String reason);

    /** Whether the connection is currently open. */
    boolean isOpen();
}
