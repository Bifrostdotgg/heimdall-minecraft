package com.heimdall.core.tunnel;

import com.heimdall.core.json.Envelope;

/**
 * Handles one type of inbound message.
 *
 * <p><strong>Never invoked on the socket's reading thread.</strong> Handlers run on the executor
 * named when they were subscribed — {@code heimdall-io} by default — precisely so a handler that
 * makes an API call, or takes a lock the main thread holds, cannot stop the socket from reading and
 * make a healthy link look dead to the heartbeat check.
 *
 * <p>The consequence, which matters when writing one: <strong>handlers do not run in wire order
 * relative to each other</strong> once the IO pool has more than one thread. Two frames of the same
 * type can be in flight simultaneously. A handler that mutates shared state has to say how.
 *
 * <p>The whole {@link Envelope} is passed, not just the payload, because a handler that wants to
 * answer needs the id to echo back.
 *
 * <p>Throwing is contained: the client logs it against the message type and carries on. One
 * malformed role-sync must not take the tunnel down.
 */
public interface TunnelMessageHandler {

    /** Handles one frame. */
    void onMessage(Envelope envelope);
}
