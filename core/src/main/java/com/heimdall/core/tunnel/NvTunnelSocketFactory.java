package com.heimdall.core.tunnel;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketFactory;
import java.io.IOException;

/**
 * The production {@link TunnelSocketFactory}, backed by nv-websocket-client.
 *
 * <p>This class and {@link NvTunnelSocket} are the <em>only</em> two in the build that name the
 * library. Everything else — reconnect policy, heartbeat, correlation, negotiation — is written
 * against {@link TunnelSocket}, so replacing the library is a two-file change.
 *
 * <p>One {@link WebSocketFactory} is held for the lifetime of the plugin and reused for every
 * attempt. That is the fix for v2's per-attempt resource leak, and it is also why the connect
 * timeout is configured here rather than per socket.
 *
 * <p>Thread-safe: {@code WebSocketFactory.createSocket} is, and nothing else here has state.
 */
public final class NvTunnelSocketFactory implements TunnelSocketFactory {

    /**
     * How long a TCP connect may take before the attempt is abandoned.
     *
     * <p>Bounded rather than left to the OS default because the OS default on Linux is over two
     * minutes, and a reconnect that takes two minutes to fail has effectively disabled the backoff
     * schedule it was supposed to feed.
     */
    private final int connectTimeoutMs;

    private final WebSocketFactory factory;

    public NvTunnelSocketFactory(int connectTimeoutMs) {
        this.connectTimeoutMs = Math.max(1000, connectTimeoutMs);
        this.factory = new WebSocketFactory();
        this.factory.setConnectionTimeout(this.connectTimeoutMs);
    }

    /** The configured connect timeout, after clamping. */
    public int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    @Override
    public TunnelSocket create(String url, TunnelSocketListener listener) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("url is required");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener is required");
        }
        try {
            WebSocket socket = factory.createSocket(url);
            return new NvTunnelSocket(socket, listener);
        } catch (IOException e) {
            // Surfaced as unchecked so the interface stays free of the library's failure model.
            // TunnelClient catches it and treats it as a connect failure, which is what it is.
            throw new IllegalStateException("could not create a WebSocket for the tunnel", e);
        }
    }
}
