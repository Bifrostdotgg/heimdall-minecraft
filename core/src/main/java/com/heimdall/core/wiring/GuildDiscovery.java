package com.heimdall.core.wiring;

import com.heimdall.core.http.ApiClient;
import com.heimdall.core.http.ApiError;
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
 * <p>v2 had {@code api.guildId} in its config and it was the single most common support problem: a
 * snowflake copied from the wrong server, or from a message link, or with a stray character. The
 * symptom is the worst available one — every request signs correctly and is refused, or worse
 * succeeds against a guild the operator does not own. The token already encodes the answer, so
 * {@code bootstrap.yml} has no guild field at all and this asks instead.
 *
 * <h2>It runs on every boot, even with a cached answer</h2>
 *
 * <p>The cached {@code guildIdCache} is <em>provisional</em>, not final: the tunnel dials with it
 * immediately, so a restart during a bot outage is not a cold start, and this asks in parallel
 * anyway. If the answer differs, the caller re-adopts.
 *
 * <p>Skipping the ask when a cache exists was the earlier design and it is a sticky-wrong-guild
 * trap. A token moved to another guild, a bot-side re-issue, a {@code bootstrap.yml} copied between
 * two servers belonging to different guilds — in every one of those the cached value is wrong, and a
 * plugin that never re-asks is wrong permanently, signing valid requests against somebody else's
 * configuration. That is exactly the failure departure D54 removed the *setting* to prevent, and
 * trusting the cache forever would have reintroduced it through the back door.
 *
 * <h2>Discovering is a state, not a failure</h2>
 *
 * <p>While this is retrying, the plugin is <em>up</em>: commands answer, modules are enabled, the
 * HTTP client has credentials. What a server with no cached guild does not have is a tunnel, because
 * its URL is keyed by guild.
 *
 * <p>Retries run on {@code heimdall-sched} with exponential backoff from
 * {@link #INITIAL_RETRY_DELAY_MS} to {@link #MAX_RETRY_DELAY_MS}, and never stop: a bot down for an
 * hour must not leave a server unable to connect without a restart.
 *
 * <p>The warning repeats on a timer rather than once. Once-forever is worse than it sounds — an
 * operator who scrolls past the boot logs, or who restarts for an unrelated reason, then has a
 * server that is silently unconfigured with nothing in the log to say so. Every
 * {@link #WARN_INTERVAL_MS} is often enough to be found and rare enough to bury nothing.
 *
 * <p>Thread-safe, and {@link #close()} is idempotent.
 */
public final class GuildDiscovery implements Registration {

    /** First retry delay. Short, because the overwhelmingly common cause is a bot still booting. */
    public static final long INITIAL_RETRY_DELAY_MS = 5_000L;

    /** The backoff ceiling. */
    public static final long MAX_RETRY_DELAY_MS = TimeUnit.MINUTES.toMillis(5);

    /** How often an unresolved guild is re-reported at WARN. */
    public static final long WARN_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15);

    /** Where discovery stands, for an operator asking {@code /hd status}. */
    public enum Status {

        /** Resolved. Nothing further will happen. */
        RESOLVED,

        /** Asking, and nothing has failed yet. The state every configured server starts in. */
        DISCOVERING,

        /**
         * The bot answered, and refused the token.
         *
         * <p>Its own state because it is the one an operator can act on immediately and the one
         * retrying will never fix: the token has been revoked, or belongs to a bot that no longer
         * knows it. Reporting that as "cannot reach the bot" sends somebody to look at their network
         * instead of at their token.
         */
        TOKEN_REFUSED,

        /** The bot could not be reached, or answered something unusable. Retrying may fix it. */
        UNREACHABLE
    }

    private final HeimdallLogger logger;
    private final ApiClient api;
    private final ScheduledExecutorService scheduler;
    private final Consumer<String> onResolved;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();

    /** Atomic for the same reason {@code ReconnectPolicy}'s is — see departure D28. */
    private final AtomicLong delayMs = new AtomicLong(INITIAL_RETRY_DELAY_MS);

    /** When the last WARN went out, so the next is on a timer rather than on every attempt. */
    private final AtomicLong lastWarnedAtMs = new AtomicLong();

    private volatile Status status = Status.DISCOVERING;
    private volatile String lastFailure = "";
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

    /** Where discovery stands. What a status command prints. */
    public Status status() {
        return status;
    }

    /** Why the last attempt failed, or {@code ""}. Included verbatim in a status line. */
    public String lastFailure() {
        return lastFailure;
    }

    /**
     * Stops asking.
     *
     * <p>Also what success calls, which is why {@link #isResolved()} reads the same flag: a resolved
     * discovery and a shut-down one are the same thing from outside — nothing further will happen.
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
        status = Status.RESOLVED;
        lastFailure = "";
        try {
            onResolved.accept(guildId);
        } catch (RuntimeException broken) {
            logger.error("the guild was resolved but wiring it up failed; this server will not "
                    + "connect its tunnel until it is restarted", broken);
        }
    }

    private void retryAfterFailure(Throwable failure) {
        final String reason =
                failure == null ? "the bot named no guild for this token" : rootMessage(failure);
        lastFailure = reason;
        status = isRefusal(failure) ? Status.TOKEN_REFUSED : Status.UNREACHABLE;

        long now = System.currentTimeMillis();
        long lastWarned = lastWarnedAtMs.get();
        boolean firstFailure = lastWarned == 0L;
        if (firstFailure || now - lastWarned >= WARN_INTERVAL_MS) {
            // Compare-and-set, so two attempts completing together produce one line rather than two.
            if (lastWarnedAtMs.compareAndSet(lastWarned, now)) {
                warnAboutFailure(firstFailure, reason);
            }
        } else {
            logger.debug(() -> "guild discovery failed again (" + reason + ")");
        }

        long delay = delayMs.get();
        delayMs.set(Math.min(MAX_RETRY_DELAY_MS, delay * 2));
        schedule(delay);
    }

    private void warnAboutFailure(boolean firstFailure, String reason) {
        String suffix = firstFailure
                ? " Commands work; the tunnel stays idle until it succeeds."
                : " Still retrying — this repeats every " + (WARN_INTERVAL_MS / 60000)
                        + " minutes until it resolves.";
        if (status == Status.TOKEN_REFUSED) {
            logger.warn("the bot refused this server's token when asked which guild it belongs to ("
                    + reason + "). It has probably been revoked or re-issued — check the Minecraft "
                    + "page of the dashboard and run setup again." + suffix);
            return;
        }
        logger.warn("could not resolve this server's guild (" + reason + ") — retrying, backing off "
                + "to " + (MAX_RETRY_DELAY_MS / 60000) + "m." + suffix);
    }

    /**
     * Whether the bot answered and said no, as opposed to not answering at all.
     *
     * <p>Only 401 and 403. A 404 means the route is missing, which is a bot too old for this
     * endpoint rather than a bad token; a 5xx is the bot having a bad moment. Calling either of those
     * a revoked token would send an operator to rotate a credential that was fine.
     */
    private static boolean isRefusal(Throwable failure) {
        Throwable current = failure;
        int guard = 0;
        while (current != null && guard++ < 16) {
            if (current instanceof ApiError) {
                int httpStatus = ((ApiError) current).httpStatus();
                return httpStatus == 401 || httpStatus == 403;
            }
            current = current.getCause();
        }
        return false;
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
        int guard = 0;
        while (current.getCause() != null && guard++ < 16) {
            current = current.getCause();
            if (current.getMessage() != null) {
                message = current.getMessage();
            }
        }
        return message == null ? failure.getClass().getSimpleName() : message;
    }
}
