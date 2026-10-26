package com.heimdall.core.wiring;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.platform.InstanceRoleDetector;
import com.heimdall.core.tunnel.ProtocolMode;
import com.heimdall.core.tunnel.ProtocolModeListener;

/**
 * Remembers what the bot says about this server's proxy, so the next start resolves the right role.
 *
 * <p>The problem it solves: a backend with proxy forwarding on detects itself as an
 * {@link ServerRole#ENFORCER}, and an enforcer leaves IP, country and subnet bans, punishment
 * announcements and join role sync to the Heimdall plugin on the proxy. Behind a plain Velocity or
 * BungeeCord there is no such plugin, so nothing did them, silently. The server cannot see what
 * its proxy runs. The bot's registry can, and every {@code identify_ack} carries its answer as
 * {@code heimdallProxy}.
 *
 * <p>On each V3 handshake this reads that answer and, when it changes what the next start would
 * resolve, records it as {@code learnedRole} in {@code bootstrap.yml}. It never changes the
 * running role: about thirty places read the role once at enable, and re-wiring all of them live
 * for a one-off correction is not worth the risk. {@code /hd status} says a restart is due until
 * one happens.
 *
 * <p>Only ever between "nothing learned" and {@link ServerRole#STANDALONE}. A configured
 * {@code role:} always wins and is never touched, a proxy never learns anything, and a server
 * without forwarding is standalone already.
 *
 * <p><strong>Off the socket thread.</strong> Mode listeners run inside the negotiator's monitor,
 * and saving takes the runtime's reconfigure lock, which {@code /hd reload} holds while it
 * reconnects the tunnel and so waits on that same monitor. The answer is read on the socket thread
 * and the save handed to the io pool, so the two locks are never held in that order.
 */
final class ProxyRoleLearner implements ProtocolModeListener {

    /** What the runtime does with a decision. Separate so the decision itself is testable. */
    interface Sink {

        /** The bot's answer for the connection that just negotiated, or {@code null}. */
        Boolean heimdallProxy();

        /** Runs {@code task} off the socket thread. */
        void submit(Runnable task);

        /** Applies the answer against what is on disk now. See {@link #decide}. */
        void learn(boolean heimdallProxy);
    }

    private final Sink sink;

    ProxyRoleLearner(Sink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink is required");
        }
        this.sink = sink;
    }

    @Override
    public void onModeChanged(ProtocolMode previous, ProtocolMode current) {
        if (current != ProtocolMode.V3) {
            return;
        }
        final Boolean answer = sink.heimdallProxy();
        if (answer == null) {
            // The bot did not say: an older bot, a lookup that timed out, a server with no network
            // yet. Not knowing is not "no proxy", so nothing changes.
            return;
        }
        sink.submit(new Runnable() {
            @Override
            public void run() {
                sink.learn(answer.booleanValue());
            }
        });
    }

    /**
     * The {@code learnedRole} to write, or {@code null} to leave the file alone.
     *
     * <p>A pure function, so every row of the policy is a test:
     *
     * <ul>
     *   <li>no detector (a proxy platform): nothing to learn;
     *   <li>a configured role, a server that is the proxy, or no forwarding: nothing to learn, and
     *       a learned value left over from before is cleared, so it cannot come back for a boot
     *       when the operator removes {@code role:} or turns forwarding back on;
     *   <li>forwarding on and the bot says no Heimdall proxy: learn {@link ServerRole#STANDALONE};
     *   <li>forwarding on and the bot says there is one: forget it ({@link ServerRole#AUTO}).
     * </ul>
     *
     * @return the new value, only when it differs from {@code learned}
     */
    static ServerRole decide(
            ServerRole configured,
            ServerRole learned,
            InstanceRoleDetector detector,
            boolean heimdallProxy) {
        if (detector == null) {
            return null;
        }
        ServerRole current = learned == ServerRole.STANDALONE ? ServerRole.STANDALONE : ServerRole.AUTO;
        boolean applies = (configured == null || configured == ServerRole.AUTO)
                && !detector.isProxy() && detector.isBehindProxy();
        ServerRole wanted = applies && !heimdallProxy ? ServerRole.STANDALONE : ServerRole.AUTO;
        return wanted == current ? null : wanted;
    }
}
