package com.heimdall.core.tunnel;

/**
 * What a {@link TunnelSocket} tells its owner.
 *
 * <p>Four callbacks, deliberately not more. The library's own listener surface has a dozen — frame
 * types, decompression errors, ping/pong frames, state transitions — and every one of them that
 * leaked through here would be a detail of the library that {@link TunnelClient} would then be
 * written against.
 *
 * <p><strong>Every callback arrives on the socket's reading thread.</strong> Implementations must
 * be fast and must not block: a handler that waits on a network call there stops the socket
 * reading, which looks exactly like a dead link to the heartbeat check that is about to fire.
 * Anything slower than parsing a frame is handed to an executor.
 *
 * <p>Both failure callbacks may be followed by {@link #onClose}, or may not be, depending on how
 * the connection died — which is precisely why the reconnect logic collapses all of its triggers
 * through a single compare-and-set instead of assuming any particular sequence.
 */
public interface TunnelSocketListener {

    /** The connection is established and frames may be sent. */
    void onOpen();

    /** A text frame arrived, already reassembled from any continuation frames. */
    void onText(String text);

    /**
     * The connection failed — either while connecting or after being established.
     *
     * <p>Not necessarily fatal on its own, and not necessarily followed by {@link #onClose}.
     */
    void onError(Throwable error);

    /**
     * The connection is gone.
     *
     * @param code the WebSocket close code, or 0 if none was exchanged
     * @param reason the close reason, possibly empty; never {@code null}
     */
    void onClose(int code, String reason);
}
