package com.heimdall.core.tunnel;

import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The periodic tick: notice a dead link, then say we are alive.
 *
 * <h2>The order is the point</h2>
 *
 * <p>The timeout check runs <em>first</em> and returns without sending anything if it fires. A tick
 * that sent its ping before checking would refresh nothing (the bot ignores client pings) but would
 * push a frame onto a socket already known to be dead, and on a wedged TCP connection that write
 * can itself block.
 *
 * <h2>Why a client ping exists at all, given the bot ignores it</h2>
 *
 * <p><strong>The bot special-cases exactly {@code identify}, {@code pong}, {@code health} and
 * {@code console_line}, and nothing else — there is no {@code ping} case.</strong> A
 * client-initiated ping is therefore filed as an unsolicited message: it gets no reply and does not
 * refresh liveness. Client liveness derives entirely from answering the <em>bot's</em> pings (or
 * from sending {@code health}, which the bot's sweep does count).
 *
 * <p>It is still sent, for two reasons. It is harmless — a frame the bot files and forgets — and it
 * is what the deployed v2 fleet does, so removing it would be a wire change made on the way past
 * rather than a decision anybody took. Making a client ping meaningful is a bot-side capability
 * decision for a later phase; it is not invented here, because a plugin built against a protocol
 * the bot does not speak looks healthy in testing and gets reaped in production.
 *
 * <p>The health message on the same tick is the one that actually does double duty: it carries the
 * dashboard's TPS and memory numbers <em>and</em> refreshes the bot's liveness timer.
 *
 * <h2>Threading</h2>
 *
 * <p>Everything here runs on {@code heimdall-ws}. {@link #start} and {@link #stop} may be called
 * from any thread and are safe to interleave.
 */
final class TunnelHeartbeat {

    private final HeimdallLogger logger;
    private final ScheduledExecutorService wsScheduler;
    private final TunnelClient client;

    private volatile ScheduledFuture<?> task;

    TunnelHeartbeat(HeimdallLogger logger, ScheduledExecutorService wsScheduler, TunnelClient client) {
        this.logger = logger;
        this.wsScheduler = wsScheduler;
        this.client = client;
    }

    /** (Re)starts the tick at the configured interval. Cancels any previous one first. */
    void start(final TunnelSettings settings) {
        stop();
        try {
            task = wsScheduler.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    tick(settings);
                }
            }, settings.heartbeatIntervalMs(), settings.heartbeatIntervalMs(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            logger.debug("heartbeat not started: the tunnel scheduler is shutting down");
        }
    }

    /** Cancels the tick. Idempotent. */
    void stop() {
        ScheduledFuture<?> running = task;
        task = null;
        if (running != null) {
            running.cancel(false);
        }
    }

    /** Whether a tick is currently scheduled. For tests and diagnostics. */
    boolean isRunning() {
        ScheduledFuture<?> running = task;
        return running != null && !running.isCancelled();
    }

    /**
     * One tick. Package-private so a test can drive it directly rather than waiting 30 seconds.
     *
     * <p>Never throws: this runs on a {@code scheduleAtFixedRate}, and an escaping exception there
     * cancels the periodic task silently — the heartbeat would simply stop, and the first anyone
     * would know is the bot reaping the connection.
     */
    void tick(TunnelSettings settings) {
        try {
            if (!client.isConnected()) {
                return;
            }

            long silentFor = client.millisSinceLastInbound();
            long allowance = settings.heartbeatIntervalMs() + settings.heartbeatTimeoutMs();
            if (silentFor > allowance) {
                client.forceReconnect("Heartbeat timeout after " + silentFor + "ms of silence");
                return;
            }

            client.sendFrame(Envelope.fresh("ping", Payload.empty()));
            sendHealth();
        } catch (RuntimeException e) {
            logger.error("heartbeat tick failed", e);
        }
    }

    private void sendHealth() {
        HealthSnapshotSource source = client.healthSource();
        if (source == null) {
            return;
        }
        Payload snapshot;
        try {
            snapshot = source.snapshot();
        } catch (RuntimeException e) {
            // A platform that cannot read its own TPS this tick is not a reason to drop the link.
            logger.error("health snapshot source threw; skipping this tick", e);
            return;
        }
        if (snapshot != null) {
            client.sendFrame(Envelope.fresh("health", snapshot));
        }
    }
}
