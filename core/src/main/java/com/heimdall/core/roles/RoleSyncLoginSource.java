package com.heimdall.core.roles;

import java.util.UUID;

/**
 * Whoever decides logins on this server, asked whether a joining player's role snapshot is already
 * taken care of.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Role sync has two ways to learn a joining player's groups. When the whitelist module decides
 * the login it calls {@code connection-attempt}, and that answer already carries the
 * {@code roleSync} block, which it passes on through {@link RoleSyncSink}. When no such answer is
 * coming, the role-sync module can ask {@code role-sync/snapshot} itself. This interface is how it
 * tells the cases apart.
 *
 * <h2>The rule: ask from the server that would have made the login call</h2>
 *
 * <p>A snapshot is requested only by the server that would have called {@code connection-attempt}
 * had the whitelist been on. On a proxied network that is the gatekeeper, not the backends: a
 * backend that leaves the login decision to its proxy ({@link Coverage#DEFERS_TO_GATEKEEPER}) does
 * not ask, because otherwise every backend join and every server switch would be a bot call where
 * there used to be none, and on a network whose LuckPerms storage is shared the backend would
 * rewrite groups the proxy had just applied.
 *
 * <p><strong>What that costs depends on the network, and the default is not the cheap case.</strong>
 *
 * <ul>
 *   <li>A standalone server: one bot call per login, whichever of the two calls it is.
 *   <li>A proxied network whose backends defer ({@code enforceOnBackend} off) or run no whitelist:
 *       one call per login per network, made by the gatekeeper, and switching servers costs
 *       nothing.
 *   <li>A proxied network with the default {@code enforceOnBackend: true}: every backend join and
 *       every server switch already makes its own {@code connection-attempt}, exactly as before this
 *       interface existed, and that answer carries the snapshot, so nothing is added. A bypassed
 *       player, though, gets a snapshot request from the proxy <em>and</em> from each backend that
 *       re-checks logins, because each of them would have made the login call.
 * </ul>
 *
 * <h2>The gap this rule leaves</h2>
 *
 * <p>Only the gatekeeper asks on a deferring network, so a server gets a join-time sync only if the
 * gatekeeper can write it. The common shape where that fails: LuckPerms runs on the backends and not
 * on the proxy. The proxy has nothing to write to and skips, the backends defer, and no server syncs
 * on join; in RCON mode the bot's RCON apply that a join request would trigger never fires either.
 * The {@code ENFORCER} role is also read from the server's own proxy-forwarding config (or set in
 * {@code bootstrap.yml}), not from the proxy, so a backend behind a proxy that does not run Heimdall
 * at all, with the whitelist off, makes no join-time call and gets no join-time sync.
 *
 * <p>This is a documented gap, not a regression: before this interface there was no join-time sync
 * at all without the whitelist module. Groups still follow every {@code role_sync} push. An
 * operator who wants join-time sync there runs LuckPerms on the proxy, or enables the whitelist
 * module (on the backends, with {@code enforceOnBackend} on, if the proxy has no LuckPerms).
 *
 * <h2>Why the whitelist module is asked, rather than its config read</h2>
 *
 * <p>"Is the whitelist enabled in the remote config" gets real deployments wrong: a whitelist that
 * is on in the dashboard but switched off locally, or that failed to start, answers nothing; a
 * bypassed player is admitted without a {@code connection-attempt}; and whether a backend defers to
 * its proxy is a whitelist <em>setting</em> ({@code enforceOnBackend}). Only the whitelist module
 * knows which of its logins reach the bot, so it answers. Like {@link RoleSyncSink} the vocabulary
 * lives in core, because neither module may depend on the other.
 *
 * <h2>Known gaps</h2>
 *
 * <ul>
 *   <li>The answer is read when the join event arrives, a moment after the login it is about. A
 *       dashboard edit landing exactly between the two can make it wrong for that one join: one
 *       redundant snapshot, or one join with no sync. Both heal on the next {@code role_sync}
 *       push or join.
 *   <li>{@link Coverage#DELIVERS} means the login <em>went</em> to the bot, not that an answer came
 *       back. When that call fails and the whitelist's fallback admits the player anyway, the join
 *       gets no sync. Reporting "attempted but failed" would need the whitelist to remember
 *       per-player outcomes across the login and the join, and a mirror hit's report is
 *       fire-and-forget, so its failure usually lands after the join. A snapshot request in that
 *       window would mostly fail for the same reason the login call did, so this is left as a gap:
 *       the next push or join repairs it.
 * </ul>
 *
 * <p>Implementations must be cheap and must not block: this is read on {@code heimdall-io} once per
 * join.
 */
public interface RoleSyncLoginSource {

    /** What a login on this server did about the player's role snapshot. */
    enum Coverage {

        /** The login went to the bot, and its answer carries (or carried) the snapshot. Do not ask. */
        DELIVERS,

        /**
         * This is a backend that leaves logins to a Heimdall gatekeeper, which is the server that
         * makes the network's one call. Do not ask here.
         */
        DEFERS_TO_GATEKEEPER,

        /**
         * Something here decides logins but did not ask the bot about this player (the bypass
         * list). This server would otherwise have made the call, so it asks.
         */
        NOT_COVERED,

        /**
         * Nothing on this server decides logins: the whitelist module is off, failed to start, or is
         * not installed. Whether to ask is then decided by the server's role alone: a backend
         * behind a gatekeeper does not, anything else does.
         */
        NO_LOGIN_CHECK
    }

    /** No login check at all. What role sync runs against when no whitelist module is wired. */
    RoleSyncLoginSource NONE = new RoleSyncLoginSource() {
        @Override
        public Coverage coverageFor(UUID playerUuid) {
            return Coverage.NO_LOGIN_CHECK;
        }
    };

    /** How this player's login was covered. Never {@code null}. */
    Coverage coverageFor(UUID playerUuid);
}
