package com.heimdall.core.tunnel;

/**
 * Opens {@link TunnelSocket}s, and is reused across every reconnect.
 *
 * <p><strong>The reuse is load-bearing.</strong> v2 constructed a fresh {@code HttpClient} per
 * connection attempt for a while, and each one carried a selector thread that never went away — a
 * server that spent a night failing to reach the bot woke up with hundreds of them. So the factory
 * is created once, holds whatever pooled machinery the library needs, and hands out one socket per
 * attempt. Implementations must therefore be safe to call repeatedly and must not accumulate
 * per-call resources.
 *
 * <p>Implementations must be thread-safe: reconnects are scheduled on {@code heimdall-ws} while the
 * caller may also be connecting from the boot thread.
 */
public interface TunnelSocketFactory {

    /**
     * Creates an unconnected socket for {@code url}, wired to {@code listener}.
     *
     * <p>The socket is not connected yet — {@link TunnelSocket#connect()} does that. Splitting the
     * two means the client can register the socket as "current" before any callback can fire,
     * which removes a race where {@code onOpen} arrives before the field it needs is set.
     *
     * @param url the full {@code ws://} or {@code wss://} URL, query string included
     * @throws RuntimeException if the socket could not be created at all — a malformed URL, an SSL
     *     context that will not initialise. The client treats this exactly as a connect failure and
     *     backs off, because from an operator's point of view it is one.
     */
    TunnelSocket create(String url, TunnelSocketListener listener);
}
