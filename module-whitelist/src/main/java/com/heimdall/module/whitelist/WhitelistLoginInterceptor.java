package com.heimdall.module.whitelist;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.model.ConnectionAction;
import com.heimdall.core.http.model.ConnectionAttemptResult;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.pipeline.Interceptor;
import com.heimdall.core.pipeline.LoginAttempt;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.core.text.Msg;
import com.heimdall.core.util.BypassList;
import com.heimdall.core.util.Strings;
import java.util.function.Supplier;

/**
 * The login gate: v2's order of checks, with v2's outcomes.
 *
 * <h2>The order, and why each step is where it is</h2>
 *
 * <ol>
 *   <li><strong>The module's own toggle.</strong> Redundant with {@code ModuleManager} almost
 *       always — a disabled module has no interceptor registered. Almost: a {@code config.push}
 *       notifies every listener, and nothing orders the manager's reconcile against anybody else's,
 *       so there is a window in which the toggle has flipped and the interceptor is still in the
 *       pipeline. One boolean read closes it.
 *   <li><strong>Role eligibility.</strong> A backend behind a gatekeeper must not re-run a decision
 *       the proxy already made, unless it is configured to. Checked here rather than through
 *       {@code roles()} because it is a <em>setting</em>, and a role excluded at registration time
 *       could not be turned back on without re-enabling the module.
 *   <li><strong>Bypass.</strong> A UUID list, not a permission: permissions are not attached during
 *       {@code AsyncPlayerPreLoginEvent}, so {@code heimdall.bypass} cannot gate a login however
 *       much an operator expects it to (issue #796 / MC-2).
 *   <li><strong>The mirror.</strong> A hit allows immediately and fires the background report.
 *   <li><strong>The bot.</strong> A miss blocks within the join budget and maps the answer.
 * </ol>
 *
 * <p>Every one of the first three returns {@link Verdict#abstain()}, not allow. Abstaining lets the
 * checks behind this one still run — the punishments gate this interceptor's priority leaves room
 * for, when it lands — whereas allowing would silently veto them. Departure D32.
 *
 * <h2>Failure is this class's problem, not the pipeline's</h2>
 *
 * <p>The fallback mode is applied <em>here</em>, in the catch, exactly as v2's was. That is
 * deliberate and departure D39 says why: "the bot is unreachable — admit or refuse?" is a policy a
 * server owner configures, and this is the only thing that knows a request failed rather than that
 * something unexpected happened. {@link #failureVerdict} stays at the default abstain and is a
 * backstop for a bug in this class, not the mechanism.
 *
 * <h2>Threading</h2>
 *
 * <p>Runs on whatever thread the platform delivers a login on — an async pre-login thread on both
 * supported platforms, which is the one place a network round trip is affordable. It blocks, on
 * purpose, and everything it blocks on is bounded.
 */
final class WhitelistLoginInterceptor implements Interceptor<LoginAttempt> {

    /**
     * Where this sits in the login pipeline.
     *
     * <p>Deliberately not zero. Punishments — a ban check — belong <em>before</em> the whitelist: a
     * banned player should be told they are banned rather than that they are not whitelisted, and
     * asking the bot whether somebody may join is wasted work for somebody who may not. Leaving room
     * below means that gate can be added in a later phase without renumbering this one, which is the
     * kind of edit that silently reorders a decision nobody re-tested.
     */
    static final int PRIORITY = 100;

    private final HeimdallLogger logger;
    private final ServerRole role;
    private final Supplier<WhitelistSettings> settings;
    private final WhitelistMirrorService mirror;
    private final ConnectionAttemptReporter reporter;
    private final Supplier<Boolean> moduleEnabled;

    WhitelistLoginInterceptor(
            HeimdallLogger logger,
            ServerRole role,
            Supplier<WhitelistSettings> settings,
            WhitelistMirrorService mirror,
            ConnectionAttemptReporter reporter,
            Supplier<Boolean> moduleEnabled) {
        this.logger = logger;
        this.role = role;
        this.settings = settings;
        this.mirror = mirror;
        this.reporter = reporter;
        this.moduleEnabled = moduleEnabled;
    }

    @Override
    public Verdict intercept(LoginAttempt attempt) {
        WhitelistSettings current = settings.get();

        if (!Boolean.TRUE.equals(moduleEnabled.get())) {
            logger.debug(() -> "whitelist is switched off; abstaining for " + attempt.username());
            return Verdict.abstain();
        }
        if (role == ServerRole.ENFORCER && !current.enforceOnBackend()) {
            logger.debug(() -> "enforceOnBackend is off and this is a backend; the gatekeeper owns "
                    + "the decision for " + attempt.username());
            return Verdict.abstain();
        }
        if (BypassList.isBypassed(current.bypassUuids(), attempt.uuid().toString())) {
            logger.debug(() -> attempt.username() + " is on the bypass list; skipping the check");
            return Verdict.abstain();
        }

        if (mirror.isWhitelisted(attempt.uuid())) {
            // The common path on a pre-warmed server, and the one v2 shipped without a report on —
            // which is how role sync, the connection history and the join feed all stopped
            // happening for everybody who was already cached. See ConnectionAttemptReporter.
            mirror.refreshUsername(attempt.uuid(), attempt.username());
            reporter.reportAsync(attempt);
            logger.debug(() -> "mirror hit for " + attempt.username());
            return Verdict.allow();
        }

        if (!reporter.isUsable()) {
            // No guild yet — this server is still discovering one, or was never set up. v2 refused
            // outright here; running the configured fallback is better, and with the default that
            // means the mirror still decides. Nothing has been asked, so there is no exception to
            // carry: the reason is stated instead.
            logger.warn("cannot check the whitelist for " + attempt.username()
                    + ": this server has not resolved its guild yet");
            return fallback(current, attempt, "no guild has been resolved yet");
        }

        try {
            return decide(current, attempt, reporter.awaitCheck(attempt, false));
        } catch (RuntimeException failed) {
            logger.warn("whitelist check failed for " + attempt.username() + ": "
                    + Strings.trimToEmpty(failed.getMessage()));
            return fallback(current, attempt, failed.getMessage());
        }
    }

    /**
     * Maps the bot's six outcomes.
     *
     * <p>All six, because a plugin that handles the first two mis-handles the rest in production —
     * and two of them are counter-intuitive enough to be worth naming here:
     *
     * <ul>
     *   <li>{@code existing_link} arrives with {@code whitelisted: true} <em>and</em> an auth code.
     *       v2 refused it, with the code in the kick message, and so does this: the player is being
     *       told how to claim an account that already exists, which is not something they can read
     *       while joining.
     *   <li>{@code show_auth_code} is also {@code whitelisted: true}, and must <strong>never</strong>
     *       be recorded in the mirror. Caching it means the next attempt is a mirror hit, the player
     *       is admitted without ever seeing a code, and they can never link — issue #796 / MC-4.
     * </ul>
     */
    private Verdict decide(
            WhitelistSettings current, LoginAttempt attempt, ConnectionAttemptResult result) {
        if (result.action() == ConnectionAction.SHOW_AUTH_CODE) {
            // Deliberately no mirror write on this branch. Both shapes that reach it — a pending
            // link and an existing-link offer — answer whitelisted:true, so a naive "cache if
            // whitelisted" is exactly the MC-4 lockout.
            logger.info("refusing " + attempt.username() + " with a link code");
            return deny(result, "You need to link your Discord account before joining.");
        }
        if (result.whitelisted()) {
            mirror.record(attempt.uuid(), attempt.username());
            logger.debug(() -> "the bot allowed " + attempt.username());
            return Verdict.allow();
        }
        if (result.action() == ConnectionAction.PENDING_APPROVAL) {
            // queuePosition is a nullable Integer on purpose (departure D1): the scheduled
            // auto-whitelist branch omits the key entirely, and v2 could not tell that from
            // "position zero", so it showed those players a queue position that did not exist. The
            // bot has already rendered whichever message applies, so nothing here has to choose.
            logger.info("refusing " + attempt.username() + ": approval pending"
                    + (result.hasQueuePosition() ? " (position " + result.queuePosition() + ")" : ""));
            return deny(result, "Your whitelist request is awaiting approval.");
        }
        if (result.revoked()) {
            // Same DENY as never-whitelisted, and worth a different log line: "you were whitelisted
            // and no longer are" is a different support conversation. Departure D6.
            logger.info("refusing " + attempt.username() + ": their whitelist was revoked");
            return deny(result, "Your whitelist access has been revoked.");
        }
        logger.info("refusing " + attempt.username() + ": not whitelisted");
        return deny(result, "You are not whitelisted on this server.");
    }

    /**
     * What happens when the bot could not be asked.
     *
     * <p>v2's three modes, unchanged. {@code whitelist-only} is the default and the interesting one:
     * it consults the mirror, which the pre-warm poll keeps as a complete copy of the whitelist — so
     * a bot redeploy is invisible to every whitelisted player rather than to the handful who
     * happened to connect recently.
     */
    private Verdict fallback(WhitelistSettings current, LoginAttempt attempt, String reason) {
        switch (current.apiFallbackMode()) {
            case ALLOW:
                logger.warn("admitting " + attempt.username() + " with the bot unreachable "
                        + "(apiFallbackMode: allow)");
                return Verdict.allow();
            case DENY:
                logger.warn("refusing " + attempt.username() + " with the bot unreachable "
                        + "(apiFallbackMode: deny)");
                return Verdict.deny(Msg.legacy(colourise(current.apiUnavailableMessage())));
            case WHITELIST_ONLY:
            default:
                if (mirror.isWhitelisted(attempt.uuid())) {
                    logger.warn("admitting " + attempt.username() + " from the mirror with the bot "
                            + "unreachable (" + reason + ")");
                    return Verdict.allow();
                }
                logger.warn("refusing " + attempt.username() + ": the bot is unreachable and the "
                        + "mirror does not hold them (" + reason + ")");
                return Verdict.deny(Msg.legacy(colourise(current.apiUnavailableMessage())));
        }
    }

    /**
     * The bot's message if it sent one, otherwise ours.
     *
     * <p>The bot renders every player-facing string — it holds the templates and has already
     * substituted {@code {player}}, {@code {code}}, {@code {position}} and {@code {reason}} into
     * them. The local text is only for the case where it did not answer at all, or answered without
     * one, which would otherwise be a kick screen with nothing on it.
     */
    private Verdict deny(ConnectionAttemptResult result, String localFallback) {
        String message = Strings.isNotBlank(result.message()) ? result.message() : localFallback;
        return Verdict.deny(Msg.legacy(colourise(message)));
    }

    /**
     * Accepts {@code &} as a colour prefix as well as {@code §}.
     *
     * <p>v2 did the same ({@code kickMessage.replace('&', '§')}) and the dashboard's templates are
     * written with ampersands, because that is what an operator can type into a web form. Without
     * this the codes reach the player as literal text.
     */
    private static String colourise(String message) {
        return message == null ? "" : message.replace('&', '§');
    }
}
