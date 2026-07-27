package com.heimdall.core.tunnel;

import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;

/**
 * Where an inbound frame goes, in order.
 *
 * <h2>The order, and why it is this one</h2>
 *
 * <ol>
 *   <li><strong>{@code ping}</strong> — answer {@code pong} with the same id, immediately, on this
 *       thread. This is the only keepalive the protocol has: the bot's sweep closes any connection
 *       that has not answered. Queueing the reply behind a module's handler on a busy IO pool is
 *       how a healthy server gets reaped.
 *   <li><strong>{@code pong}</strong> — refresh liveness. The bot only sends these in reply to a
 *       client ping it does not otherwise act on, so in practice this arm rarely fires; it stays
 *       because v2 needed it and costs one string comparison.
 *   <li><strong>Correlated replies</strong> — an id somebody is waiting on. Checked before the
 *       subscription registry so a reply can never be delivered twice, once to its future and once
 *       to a subscriber of the same type.
 *   <li><strong>Protocol frames</strong> — {@code identify_ack} and {@code config.push}, handled by
 *       the negotiator on this thread. They are the tunnel's own business and modules observe their
 *       effects through {@code RemoteConfig} and the mode listeners rather than the raw frames.
 *   <li><strong>Subscriptions</strong> — dispatched onto each subscriber's executor.
 *   <li><strong>The unhandled hook</strong> — one last chance, for the platform-side SPI, before
 *       the frame is written off with a debug line. Dispatched onto {@code heimdall-io} like any
 *       subscriber: a third-party plugin's handler is the last code that should be trusted with the
 *       socket's reading thread.
 * </ol>
 *
 * <p>Steps 1-3 and 5-6 are v2's exact order. Step 4 is new, and is the only insertion: v3's two
 * protocol frames are neither pings nor replies to anything the plugin asked for.
 *
 * <h2>Liveness</h2>
 *
 * <p><strong>Every</strong> parseable inbound frame refreshes the client's last-seen clock, not
 * just pings and pongs. A connection that is delivering role syncs is demonstrably alive, and
 * v2's narrower rule — only a ping or a pong counts — meant a busy link could still be aborted by
 * its own heartbeat if the bot happened to be too busy to sweep. See departure D25.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #dispatch} runs on the socket's reading thread and everything it does directly is
 * O(microseconds). The only work handed off is to subscribers, which never run here.
 */
final class TunnelDispatcher {

    private final HeimdallLogger logger;
    private final TunnelClient client;
    private final HandshakeNegotiator negotiator;
    private final PendingRequests pending;
    private final SubscriptionRegistry subscriptions;

    TunnelDispatcher(
            HeimdallLogger logger,
            TunnelClient client,
            HandshakeNegotiator negotiator,
            PendingRequests pending,
            SubscriptionRegistry subscriptions) {
        this.logger = logger;
        this.client = client;
        this.negotiator = negotiator;
        this.pending = pending;
        this.subscriptions = subscriptions;
    }

    /** Routes one raw text frame. Never throws. */
    void dispatch(String text) {
        Envelope envelope;
        try {
            envelope = Envelope.parse(text);
        } catch (RuntimeException e) {
            logger.error("could not parse an inbound tunnel frame", e);
            return;
        }
        if (envelope == null) {
            // Missing or falsy id/type. The bot's own guard rejects these too, so this is the
            // frame being wrong rather than us failing to understand it.
            logger.debug("ignoring a malformed tunnel frame");
            return;
        }

        client.markAlive();

        try {
            route(envelope);
        } catch (RuntimeException e) {
            logger.error("failed to handle tunnel frame '" + envelope.type() + "'", e);
        }
    }

    private void route(Envelope envelope) {
        String type = envelope.type();

        if ("ping".equals(type)) {
            client.sendFrame(Envelope.of(envelope.id(), "pong", Payload.empty()));
            return;
        }
        if ("pong".equals(type)) {
            return;
        }
        if (pending.complete(envelope.id(), envelope.payload())) {
            return;
        }
        if (negotiator.handle(envelope)) {
            return;
        }
        if (subscriptions.dispatch(envelope)) {
            return;
        }

        if (client.dispatchUnhandled(envelope)) {
            return;
        }
        logger.debug("no handler for tunnel message '" + type + "'");
    }
}
