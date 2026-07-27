package com.heimdall.core.tunnel;

import com.heimdall.core.json.Envelope;

/**
 * Puts one frame on the wire.
 *
 * <p>The seam between {@link TunnelClient} — which owns the socket, the reconnect state and the
 * decision about whether sending is even possible — and the collaborators that only need to say
 * something: the handshake negotiator and the heartbeat. Neither of them should be able to reach
 * the socket, and neither should have to know that a send while disconnected is a silent no-op
 * rather than an error.
 */
interface FrameSender {

    /** Sends the frame if the tunnel is up; does nothing if it is not. */
    void sendFrame(Envelope envelope);
}
