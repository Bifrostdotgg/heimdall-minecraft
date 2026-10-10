package com.heimdall.shell.contract;

import com.heimdall.core.json.Payload;
import java.util.concurrent.CompletableFuture;

/**
 * The running core's tunnel, as the shell's permanent {@code HeimdallTunnel} sees it.
 *
 * <h2>Why third-party plugins never hold a core object</h2>
 *
 * <p>Other plugins find the tunnel through {@code HeimdallTunnelProvider} or Bukkit's
 * {@code ServicesManager} and may keep the reference for as long as they like. If that reference
 * were the core's own service, every swap would leave them publishing into a stopped core, and their
 * reference would pin its classloader for good. So the object they hold is the shell's, it never
 * changes, and it forwards to whichever backend the current core has bound. Their {@code on(...)}
 * subscriptions live in the shell too, so they survive a swap without the plugin knowing one
 * happened.
 *
 * <p>Between generations there is no backend: the shell's tunnel then reports itself disconnected,
 * drops publishes and fails requests fast, exactly as during an ordinary reconnect.
 */
public interface TunnelBackend {

    /** The running core's version. */
    String version();

    boolean isConnected();

    /** Fire-and-forget; drops while disconnected. */
    void publish(String type, Payload payload);

    /** A correlated request; fails fast while disconnected. */
    CompletableFuture<Payload> request(String type, Payload payload, long timeoutMs);

    /** Sends {@code type} as the correlated reply to request {@code requestId}. */
    void reply(String requestId, String type, Payload payload);
}
