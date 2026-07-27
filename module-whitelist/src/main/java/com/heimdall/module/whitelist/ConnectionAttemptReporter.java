package com.heimdall.module.whitelist;

import com.heimdall.core.concurrent.InFlight;
import com.heimdall.core.http.ApiClient;
import com.heimdall.core.http.model.ConnectionAttempt;
import com.heimdall.core.http.model.ConnectionAttemptResult;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.pipeline.LoginAttempt;
import com.heimdall.core.platform.LuckPermsBridge;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.roles.RoleSyncSink;
import com.heimdall.core.util.Strings;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Every call to {@code connection-attempt}, and the two ways a login reaches it.
 *
 * <h2>The report rides a mirror hit too, and that is the 2.4.0 regression</h2>
 *
 * <p>A pre-warmed mirror holds every whitelisted player, so the fast path is the <em>common</em>
 * path — for most joins on most servers, the mirror answers and the bot is never asked. v2 shipped
 * with the API call skipped on that path, and three things silently stopped happening: role sync,
 * the bot's connection history and {@code lastSeen}, and the dashboard's join feed. A role change
 * made in Discord simply never reached the server, for anybody who was already cached, which is
 * everybody.
 *
 * <p>So a mirror hit fires the report anyway — {@link #reportAsync} — and it is fire-and-forget by
 * construction: it must never block the login, never change its outcome, and never surface a
 * failure to the player, who is already in. A non-whitelisted answer on that path is deliberately
 * <strong>not</strong> acted on; revocation propagates through the pre-warm prune, and kicking
 * somebody mid-join on the strength of a racing response is a worse trade.
 *
 * <h2>Collapsing, not caching</h2>
 *
 * <p>{@link InFlight} keyed by UUID means a reconnect storm produces one request per player rather
 * than one per attempt. It is deliberately not v2's 30-second response cache, which is what caused
 * the 2.4.0 outage: that cache replayed whole responses, including their {@code roleSync} blocks, so
 * a join a second after a role change reverted the groups that had just been set. Nothing is
 * retained here once a request completes (departure D7).
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #awaitCheck} blocks the caller and is called from a platform's async pre-login thread,
 * which is where a login can afford to wait. {@link #reportAsync} returns immediately and completes
 * on {@code heimdall-io}. The role-sync hand-off happens on whichever of those completed the
 * request, so the sink must not block — see {@link RoleSyncSink}.
 */
final class ConnectionAttemptReporter {

    private final HeimdallLogger logger;
    private final ApiClient api;
    private final PlatformFacade platform;
    private final Executor io;
    private final Supplier<RoleSyncSink> roleSync;

    private final InFlight<String, ConnectionAttemptResult> inFlight =
            new InFlight<String, ConnectionAttemptResult>();

    ConnectionAttemptReporter(
            HeimdallLogger logger,
            ApiClient api,
            PlatformFacade platform,
            Executor io,
            Supplier<RoleSyncSink> roleSync) {
        this.logger = logger;
        this.api = api;
        this.platform = platform;
        this.io = io;
        this.roleSync = roleSync;
    }

    /** Whether there is a usable client at all — false while the guild is still being discovered. */
    boolean isUsable() {
        return api != null && api.settings().isUsable();
    }

    /**
     * Asks the bot and waits, within the join budget.
     *
     * <p>The budget is {@code ApiSettings.joinTimeoutMs()} — the full retry budget plus a second of
     * slack, because {@code HttpURLConnection} applies its timeout twice (connect, then read) and
     * the {@code + 1000} v2 had was silently dropped in an earlier rewrite. See departure D16.
     *
     * @throws RuntimeException whatever the request failed with, so the caller can apply its
     *     configured fallback mode — which is a policy decision the interceptor owns, not this class
     */
    ConnectionAttemptResult awaitCheck(LoginAttempt login, boolean currentlyWhitelisted) {
        CompletableFuture<ConnectionAttemptResult> pending = submit(login, currentlyWhitelisted);
        try {
            return pending.get(api.settings().joinTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while checking the whitelist", interrupted);
        } catch (TimeoutException tooSlow) {
            throw new IllegalStateException(
                    "the bot did not answer within " + api.settings().joinTimeoutMs() + "ms", tooSlow);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("the whitelist check failed", cause == null ? failed : cause);
        }
    }

    /**
     * Fires the report without waiting, for a login the mirror has already allowed.
     *
     * <p>Nothing about the login depends on the outcome. A failure is a debug line: the player is on
     * the server, and telling an operator that a background bookkeeping call failed for somebody who
     * joined perfectly well is noise that trains them to ignore the log.
     */
    void reportAsync(final LoginAttempt login) {
        submit(login, true).whenComplete(new BiConsumer<ConnectionAttemptResult, Throwable>() {
            @Override
            public void accept(ConnectionAttemptResult result, Throwable failure) {
                if (failure != null) {
                    logger.debug(() -> "the background connection report for " + login.username()
                            + " failed; they were admitted from the mirror regardless: " + failure);
                }
            }
        });
    }

    /**
     * Starts — or joins — the request for this player, and applies whatever comes back.
     *
     * <p>The role snapshot is applied here, once, so the blocking path and the fire-and-forget path
     * cannot drift — which is exactly what they did in v2, where they were separate methods and the
     * cache-hit one was missing role sync entirely.
     *
     * <p>Recording in the mirror is deliberately <em>not</em> done here. That is a decision about
     * the login's outcome rather than about the response — a {@code show_auth_code} answer is
     * {@code whitelisted: true} and must never be cached (issue #796 / MC-4) — so it belongs to the
     * interceptor, which is the thing that knows what it did with the answer.
     */
    private CompletableFuture<ConnectionAttemptResult> submit(
            final LoginAttempt login, final boolean currentlyWhitelisted) {
        String key = login.uuid().toString().toLowerCase(Locale.ROOT);
        return inFlight.submit(key, new Supplier<CompletableFuture<ConnectionAttemptResult>>() {
            @Override
            public CompletableFuture<ConnectionAttemptResult> get() {
                // COMPOSED, not blocked on. An earlier version read the groups with a five-second
                // get() right here, inside the supplier — which runs on the caller's thread, so the
                // real worst case was five seconds PLUS the retry budget while awaitCheck's own
                // bound only covered the second half. The javadoc claimed joinTimeoutMs bounded the
                // wait and it did not.
                //
                // thenCompose keeps the whole thing inside one future, so awaitCheck's timeout is
                // the true ceiling on everything a joining player waits for. Non-async on purpose:
                // the continuation builds a request and hands it to ApiClient, which does its own
                // hop onto heimdall-io, and the conformance rules ban the executor-less *Async
                // overloads rather than the synchronous stages.
                return currentGroups(login).thenCompose(groups -> {
                    ConnectionAttempt attempt = ConnectionAttempt
                            .builder(login.username(), login.uuid().toString())
                            .ip(login.ipAddress())
                            .currentlyWhitelisted(currentlyWhitelisted)
                            .currentGroups(groups)
                            .build();
                    return api.connectionAttempt(attempt);
                }).whenComplete(new BiConsumer<ConnectionAttemptResult, Throwable>() {
                    @Override
                    public void accept(ConnectionAttemptResult result, Throwable failure) {
                        if (failure == null && result != null) {
                            applyRoleSync(login, result);
                        }
                    }
                });
            }
        });
    }

    /**
     * Hands the response's role snapshot to whoever applies them, if there is one to apply.
     *
     * <p>Only for a player the bot vouched for, which is v2's rule: a denied login has no groups to
     * settle, and a pending one is not on the server. The three states of the directive are the
     * sink's to interpret — see departure D2 for why they are not two.
     */
    private void applyRoleSync(LoginAttempt login, ConnectionAttemptResult result) {
        if (!result.whitelisted()) {
            return;
        }
        try {
            roleSync.get().applyOnJoin(login.uuid(), login.username(), result.roleSync());
        } catch (RuntimeException broken) {
            // Contained here rather than left to the caller: on the blocking path this runs inside
            // the future the login is waiting on, and a role-sync bug must not turn into a refused
            // login.
            logger.error("applying the role snapshot for " + login.username() + " failed; their "
                    + "login is unaffected", broken);
        }
    }

    /**
     * The groups the player currently holds, for the bot to diff against.
     *
     * <p>Empty when LuckPerms is absent, which is the honest answer — the bot then has nothing to
     * diff and makes no group decisions. Empty on a timeout is the answer that is <em>not</em>
     * honest, so that case is logged.
     */
    private CompletableFuture<List<String>> currentGroups(LoginAttempt login) {
        LuckPermsBridge bridge = platform.integrations().luckPerms().orElse(null);
        if (bridge == null || !bridge.isAvailable()) {
            return CompletableFuture.completedFuture(Collections.<String>emptyList());
        }
        try {
            return bridge.getPlayerGroups(login.uuid()).exceptionally(broken -> {
                // Empty here is not an honest answer, it is a fallback — the bot then diffs against
                // nothing and concludes every managed group needs adding, which is issue #796 /
                // MC-11. So it is said out loud rather than passed off as "no groups".
                logger.warn("could not read " + login.username() + "'s LuckPerms groups; sending an "
                        + "empty set, so the bot will diff against nothing for this login "
                        + "(issue #796 / MC-11): " + Strings.trimToEmpty(broken.getMessage()));
                return Collections.<String>emptyList();
            });
        } catch (RuntimeException bridgeRefused) {
            // The bridge throwing synchronously rather than failing its future. Not a shape
            // LuckPermsIntegration produces, but a fake or a future implementation might.
            logger.warn("the LuckPerms bridge refused a group read for " + login.username() + ": "
                    + Strings.trimToEmpty(bridgeRefused.getMessage()));
            return CompletableFuture.completedFuture(Collections.<String>emptyList());
        }
    }
}
