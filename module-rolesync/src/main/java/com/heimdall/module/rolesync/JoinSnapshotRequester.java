package com.heimdall.module.rolesync;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.ApiError;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.http.model.RoleSyncDirective;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.LuckPermsBridge;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.roles.RoleSyncLoginSource;
import com.heimdall.core.roles.RoleSyncSink;
import com.heimdall.core.session.PlayerSessionListener;
import com.heimdall.core.util.Strings;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Asks the bot for a joining player's role snapshot when no login answer is going to carry one.
 *
 * <h2>When it asks, and when it stays quiet</h2>
 *
 * <p>The whitelist module's {@code connection-attempt} answer already carries the {@code roleSync}
 * block, and it hands it over through {@link RoleSyncSink}. The rule is that the snapshot is
 * requested only by the server that would have made that call had the whitelist been on, so a
 * login costs one bot call per <em>network</em>. {@link RoleSyncLoginSource.Coverage} carries the
 * answer:
 *
 * <ul>
 *   <li>{@code DELIVERS}: the login went to the bot; do not ask.
 *   <li>{@code DEFERS_TO_GATEKEEPER}: a backend with {@code enforceOnBackend} off, leaving logins
 *       to its proxy; do not ask. Asking here would turn every backend join and server switch into
 *       a bot call, and with shared LuckPerms storage it would rewrite what the proxy just applied.
 *   <li>{@code NOT_COVERED}: the whitelist runs here but skipped this player (bypass list); ask.
 *   <li>{@code NO_LOGIN_CHECK}: nothing here decides logins (the whitelist is off, failed, or is not
 *       installed). The server's role decides, with the signal the whitelist uses to defer: an
 *       {@link ServerRole#ENFORCER} backend sits behind a gatekeeper and does not ask; anything else
 *       does.
 * </ul>
 *
 * <p>The request carries what {@code connection-attempt} would have: the player's current
 * LuckPerms groups (see {@link #currentGroups} for the missing-versus-empty contract) and, through
 * the client, their Bedrock identity.
 *
 * <p>It also stays quiet when the answer could not be used: no bot link yet (no guild, or never set
 * up), or no LuckPerms to write to. The LuckPerms check here is silent on purpose. The
 * "LuckPerms is not available" warning belongs to the apply path, where role sync was actually
 * asked to do something; saying it on the first join of every LuckPerms-less server would warn
 * operators who never configured role sync at all.
 *
 * <h2>Never on the join thread, and never blocking anything</h2>
 *
 * <p>Session listeners run on {@code heimdall-io} already (see {@code PlayerSessionEvents}), and
 * the request itself is asynchronous on the same pool: this method fires it and returns. The
 * answer goes to {@link RoleSyncSink#applyOnJoin}, which defers the actual LuckPerms write until the
 * join has settled, exactly as it does for a login answer. The sink is the module rather than the
 * applier of the moment, so a module switched off while the request was in flight applies nothing.
 *
 * <h2>Failure is "no sync this time", said once per reason</h2>
 *
 * <p>A failed request changes nothing: the next {@code role_sync} push or the next join is the
 * retry. Each failure is logged at debug, and the first of each <em>kind</em> (an
 * {@link ApiError} code, or an exception type) also at warning, because a bot rejecting every
 * snapshot request is something an operator must be able to find, and a warning per join during an
 * outage is not. The set of reported kinds is cleared by the next success, so a later outage is
 * reported again rather than hidden by an earlier one; it is bounded by the handful of codes the
 * bot and the transport can produce. A failed LuckPerms group read is reported the same way, in
 * its own set.
 *
 * <p>A {@code 404} is not a failure at all: the client maps it to
 * {@link RoleSyncDirective#absent()} (a bot older than this plugin, or a guild without the
 * Minecraft integration), and the applier logs the absent case at debug like any other.
 */
final class JoinSnapshotRequester implements PlayerSessionListener {

    private final HeimdallLogger logger;
    private final Supplier<HeimdallApi> api;
    private final Supplier<RoleSyncLoginSource> loginSource;
    private final Supplier<ServerRole> role;
    private final Supplier<LuckPermsBridge> luckPerms;
    private final RoleSyncSink sink;

    private final Set<String> reportedFailures = ConcurrentHashMap.newKeySet();
    private final Set<String> reportedGroupReadFailures = ConcurrentHashMap.newKeySet();

    /**
     * @param luckPerms the bridge when LuckPerms is present and available, otherwise {@code null};
     *     resolved per join and quietly, with no absence warning (see the class javadoc)
     */
    JoinSnapshotRequester(
            HeimdallLogger logger,
            Supplier<HeimdallApi> api,
            Supplier<RoleSyncLoginSource> loginSource,
            Supplier<ServerRole> role,
            Supplier<LuckPermsBridge> luckPerms,
            RoleSyncSink sink) {
        this.logger = logger;
        this.api = api;
        this.loginSource = loginSource;
        this.role = role;
        this.luckPerms = luckPerms;
        this.sink = sink;
    }

    @Override
    public void onPlayerSession(PlayerHandle player, long timestampMs) {
        final UUID uuid = player.uuid();
        final String username = player.name();
        if (uuid == null || Strings.isBlank(username)) {
            return;
        }
        if (!shouldAsk(uuid, username)) {
            return;
        }
        final HeimdallApi bot = api.get();
        if (!bot.isUsable()) {
            logger.debug(() -> "no role-sync snapshot request for " + username + ": "
                    + bot.describe());
            return;
        }
        LuckPermsBridge bridge = luckPerms.get();
        if (bridge == null) {
            logger.debug(() -> "no role-sync snapshot request for " + username
                    + ": LuckPerms is not available here, so there is nothing to write it to");
            return;
        }

        CompletableFuture<RoleSyncDirective> request;
        try {
            request = currentGroups(bridge, uuid, username).thenCompose(
                    groups -> bot.requestRoleSyncSnapshot(username, uuid.toString(), groups));
        } catch (RuntimeException refused) {
            report(username, refused);
            return;
        }
        request.whenComplete(new BiConsumer<RoleSyncDirective, Throwable>() {
            @Override
            public void accept(RoleSyncDirective directive, Throwable failure) {
                if (failure != null) {
                    report(username, failure);
                    return;
                }
                reportedFailures.clear();
                sink.applyOnJoin(uuid, username,
                        directive == null ? RoleSyncDirective.absent() : directive);
            }
        });
    }

    /** The per-network rule; see the class javadoc. */
    private boolean shouldAsk(UUID uuid, final String username) {
        RoleSyncLoginSource.Coverage coverage = loginSource.get().coverageFor(uuid);
        switch (coverage == null ? RoleSyncLoginSource.Coverage.NO_LOGIN_CHECK : coverage) {
            case DELIVERS:
                logger.debug(() -> username + "'s login went to the bot, so its answer carries the "
                        + "role snapshot; not asking again");
                return false;
            case DEFERS_TO_GATEKEEPER:
                logger.debug(() -> "not asking for " + username + "'s role snapshot: this backend "
                        + "leaves logins to its gatekeeper, which makes the network's one call");
                return false;
            case NO_LOGIN_CHECK:
                if (role.get() == ServerRole.ENFORCER) {
                    logger.debug(() -> "not asking for " + username + "'s role snapshot: this is a "
                            + "backend behind a gatekeeper, which makes the network's one call");
                    return false;
                }
                return true;
            case NOT_COVERED:
            default:
                return true;
        }
    }

    /**
     * The player's LuckPerms groups for the request, or {@code null} to leave the key out.
     *
     * <p>Sent for the same reason {@code connection-attempt} sends them: the bot uses them for its
     * background refresh and its RCON diff. The bot's contract distinguishes the two ways of saying
     * nothing: a <strong>missing</strong> {@code currentGroups} means "unknown", and the bot does no
     * diff and writes no audit row for it; an <strong>empty list</strong> means "holds no groups",
     * and the bot diffs against that. So a failed read leaves the key out. Sending {@code []} there
     * would be the false diff of issue #796 / MC-11, which {@code connection-attempt} still risks
     * because it has no way to say "unknown".
     *
     * <p>A failed read is warned about the way the connection-attempt reporter does, because a
     * LuckPerms whose storage cannot be read is an operator problem, but once per kind of failure
     * rather than per join; the set is cleared by the next successful read.
     */
    private CompletableFuture<List<String>> currentGroups(
            LuckPermsBridge bridge, UUID uuid, final String username) {
        try {
            return bridge.getPlayerGroups(uuid).handle((groups, broken) -> {
                if (broken != null) {
                    reportGroupRead(username, broken);
                    return null;
                }
                reportedGroupReadFailures.clear();
                return groups;
            });
        } catch (RuntimeException refused) {
            reportGroupRead(username, refused);
            return CompletableFuture.completedFuture(null);
        }
    }

    private void reportGroupRead(String username, Throwable failure) {
        Throwable cause = unwrap(failure);
        String reason = Strings.isBlank(cause.getMessage())
                ? cause.getClass().getSimpleName() : cause.getMessage();
        if (reportedGroupReadFailures.add(cause.getClass().getName())) {
            logger.warn("could not read " + username + "'s LuckPerms groups for the role-sync "
                    + "snapshot request; sending it without currentGroups, which the bot reads as "
                    + "unknown (no diff): " + reason + ". Further failures of this kind are logged "
                    + "at debug only.");
        } else {
            logger.debug(() -> "LuckPerms group read for " + username + " failed again: " + reason);
        }
    }

    private void report(String username, Throwable failure) {
        final Throwable cause = unwrap(failure);
        final String kind = cause instanceof ApiError
                ? "ApiError " + ((ApiError) cause).code()
                : cause.getClass().getSimpleName();
        final String reason = Strings.isBlank(cause.getMessage()) ? kind : cause.getMessage();
        if (reportedFailures.add(kind)) {
            logger.warn("could not fetch a role-sync snapshot for " + username + " (" + reason
                    + "); their groups are left as they are until the next role_sync push or join. "
                    + "Further failures of this kind are logged at debug only.");
        } else {
            logger.debug(() -> "role-sync snapshot for " + username + " failed: " + reason);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }
}
