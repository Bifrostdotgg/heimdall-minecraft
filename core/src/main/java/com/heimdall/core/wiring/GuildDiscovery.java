package com.heimdall.core.wiring;

import com.heimdall.core.http.ApiClient;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.Registration;
import com.heimdall.core.util.Strings;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Asks the bot which guild this server's token belongs to, and keeps asking until it answers.
 *
 * <h2>Why the guild is discovered rather than configured</h2>
 *
 * <p>v2 had {@code api.guildId} in its config and it was the single most common support problem:
 * a snowflake copied from the wrong server, or from a message link, or with a stray character. The
 * symptom is the worst available one — every request signs correctly and is refused, or worse
 * succeeds against a guild the operator does not own. The token already encodes the answer, so
 * {@code bootstrap.yml} has no guild field at all and this asks instead.
 *
 * <h2>Discovering is a state, not a failure</h2>
 *
 * <p>While this is retrying, the plugin is <em>up</em>: commands answer, modules are enabled, the
 * HTTP client has credentials. What it does not have is a tunnel, because the tunnel's URL is keyed
 * by guild. So {@link HeimdallRuntime} dials only once this succeeds, and says which state it is in
 * exactly once rather than on every attempt.
 *
 * <p>Retries run on {@code heimdall-sched} with exponential backoff from
 * {@link #INITIAL_RETRY_DELAY_MS} to {@link #MAX_RETRY_DELAY_MS}, and never stop: a bot that is
 * down for an hour must not leave a server permanently unable to connect without a restart. The
 * ceiling is what keeps that from being a poll — five minutes is far below the cost of a manual
 * intervention and far above the cost of a request.
 *
 * <p>Thread-safe, and {@link #close()} is idempotent.
 */
public final class GuildDiscovery implements Registration {

    /** First retry delay. Short, because the overwhelmingly common cause is a bot still booting. */
    public static final long INITIAL_RETRY_DELAY_MS = 5_000L;

    /** The backoff ceiling. */
    public static final long MAX_RETRY_DELAY_MS = TimeUnit.MINUTES.toMillis(5);

    private final HeimdallLogger logger;
    private final ApiClient api;
    private final ScheduledExecutorService scheduler;
    private final Consumer<String> onResolved;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();

    /** Atomic for the same reason {@code ReconnectPolicy}'s is — see departure D28. */
    private final AtomicLong delayMs = new AtomicLong(INITIAL_RETRY_DELAY_MS);

    private volatile ScheduledFuture<?> pending;

    public GuildDiscovery(
            HeimdallLogger logger,
            ApiClient api,
            ScheduledExecutorService scheduler,
            Consumer<String> onResolved) {
        if (logger == null || api == null || scheduler == null || onResolved == null) {
            throw new IllegalArgumentException(
                    "logger, api, scheduler and the resolution callback are all required");
        }
        this.logger = logger;
        this.api = api;
        this.scheduler = scheduler;
        this.onResolved = onResolved;
    }

    /**
     * Starts discovering. Idempotent — a second call while one is already running does nothing.
     *
     * <p>Returns immediately: the request runs on the API client's own IO pool.
     */
    public void start() {
        if (closed.get() || !running.compareAndSet(false, true)) {
            return;
        }
        attempt();
    }

    /** Whether a guild has been resolved and this has stopped asking. */
    public boolean isResolved() {
        return closed.get();
    }

    /**
     * Stops asking.
     *
     * <p>Also what success calls, which is why {@link #isResolved()} reads the same flag: a resolved
     * discovery and a shut-down one are the same thing from the outside — nothing further will
     * happen.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> armed = pending;
        pending = null;
        if (armed != null) {
            armed.cancel(false);
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void attempt() {
        if (closed.get()) {
            return;
        }
        api.identify().whenComplete((guildId, failure) -> {
            if (closed.get()) {
                return;
            }
            if (failure == null && Strings.isNotBlank(guildId)) {
                succeed(guildId.trim());
                return;
            }
            retryAfterFailure(failure);
        });
    }

    private void succeed(String guildId) {
        // Closed before the callback, so a callback that itself takes a while — it persists the
        // bootstrap file and dials the tunnel — cannot race a retry that was already in flight.
        close();
        logger.info("resolved guild " + guildId + " from this server's token");
        try {
            onResolved.accept(guildId);
        } catch (RuntimeException broken) {
            logger.error("the guild was resolved but wiring it up failed; this server will not "
                    + "connect its tunnel until it is restarted", broken);
        }
    }

    private void retryAfterFailure(Throwable failure) {
        long delay = delayMs.get();
        // Logged at warn on the first attempt and at debug afterwards. A bot that is down for an
        // hour would otherwise write the same line into the server log every five minutes, which
        // buries whatever else went wrong that hour.
        String reason = failure == null ? "the bot named no guild for this token" : rootMessage(failure);
        if (delay == INITIAL_RETRY_DELAY_MS) {
            logger.warn("could not resolve this server's guild (" + reason + ") — retrying every "
                    + (delay / 1000) + "s, backing off to " + (MAX_RETRY_DELAY_MS / 60000)
                    + "m. Commands work; the tunnel stays idle until it succeeds.");
        } else {
            logger.debug(() -> "guild discovery failed again (" + reason + ")");
        }
        delayMs.set(Math.min(MAX_RETRY_DELAY_MS, delay * 2));
        schedule(delay);
    }

    private void schedule(long delay) {
        try {
            pending = scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    attempt();
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            logger.debug("not retrying guild discovery: the scheduler is shutting down");
            close();
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        String message = failure.getMessage();
        while (current.getCause() != null) {
            current = current.getCause();
            if (current.getMessage() != null) {
                message = current.getMessage();
            }
        }
        return message == null ? failure.getClass().getSimpleName() : message;
    }
}
