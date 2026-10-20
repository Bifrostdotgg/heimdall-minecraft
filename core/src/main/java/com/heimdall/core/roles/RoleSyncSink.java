package com.heimdall.core.roles;

import com.heimdall.core.http.model.RoleSyncDirective;
import java.util.List;
import java.util.UUID;

/**
 * Whoever applies a role snapshot that arrived on a login response.
 *
 * <h2>Why this is in core rather than in either module</h2>
 *
 * <p>The bot returns a {@code roleSync} block on {@code connection-attempt}, so the module that
 * makes that call — whitelist — is the one holding the directive, and the module that knows what
 * LuckPerms is — rolesync — is the one that can apply it. Neither may depend on the other: they are
 * independently toggleable, and a compile edge between them would mean disabling one broke the
 * build of the other.
 *
 * <p>So the vocabulary lives here, beside {@link RoleSyncDirective}, which core already owns. It is
 * one method and a no-op constant, which is the whole of what the two modules have to agree on.
 *
 * <h2>The sink is always present, and may be inert</h2>
 *
 * <p>A whitelist module wired to {@link #NONE} — because rolesync is not installed, or is switched
 * off — must behave exactly as it does with a live one. That is why this is a no-op default rather
 * than a nullable reference: a null would put the same "is role sync available" branch at every
 * call site, and somebody would eventually forget it.
 *
 * <p>An implementation is reached while the rolesync module may itself be disabled, so deciding
 * whether to do anything is the implementation's job, not the caller's.
 *
 * <h2>Threading</h2>
 *
 * <p>Called from the login path — a platform's async pre-login thread — and from the fire-and-forget
 * report that rides a mirror hit, which completes on {@code heimdall-io}. Implementations must not
 * block the caller: the login path is a player waiting to join.
 */
public interface RoleSyncSink {

    /**
     * The stamp of a delivery whose request was never stamped: treated as newer than anything, so
     * it is recorded exactly as an unstamped delivery always was.
     */
    long UNSTAMPED = Long.MAX_VALUE;

    /** Applies nothing. What a whitelist module runs against when role sync is not available. */
    RoleSyncSink NONE = new RoleSyncSink() {
        @Override
        public void applyOnJoin(UUID playerUuid, String username, RoleSyncDirective directive) {
        }

        @Override
        public void groupsDelivered(UUID playerUuid, List<String> currentGroups) {
        }
    };

    /**
     * Applies the snapshot the bot returned for a player who is joining.
     *
     * <p>The directive's three states are the implementation's to honour, and they are not the same:
     * absent means no snapshot exists yet, disabled means the bot is driving LuckPerms over RCON and
     * the plugin must keep out, and only the third carries groups. See departure D2.
     *
     * @param username the name as the platform reported it, for logging — never normalised (D8)
     */
    void applyOnJoin(UUID playerUuid, String username, RoleSyncDirective directive);

    /**
     * {@link #applyOnJoin(UUID, String, RoleSyncDirective)} for an answer to a request stamped by
     * {@link #groupsRequestStarting}, the same stamp the call's {@link #groupsDelivered} carries.
     *
     * <p>The stamp says when the bot was asked, which decides whether the answer is still the bot's
     * latest word: a {@code role_sync} push applied after the request started is newer than what the
     * answer says about that player's groups, and a mode switch in the config after it started is
     * newer than what the answer says about who drives LuckPerms. The default ignores the stamp.
     *
     * @param requestStamp what {@link #groupsRequestStarting} returned for this request, or
     *     {@link #UNSTAMPED}
     */
    default void applyOnJoin(
            UUID playerUuid, String username, RoleSyncDirective directive, long requestStamp) {
        applyOnJoin(playerUuid, username, directive);
    }

    /**
     * Says that a join-time call from <em>this</em> server reached the bot, successfully, carrying
     * these {@code currentGroups}.
     *
     * <p>Reverse role sync (in-game rank to Discord role) needs to know what the bot has already been
     * told about a player, so that it reports only what the bot does not know. The only honest source
     * for that is a call that actually delivered the groups: reading LuckPerms locally at join says
     * nothing about the bot when this server made no call (a backend deferring to its gatekeeper), or
     * the call failed, or the bot was not reachable. So a caller invokes this only after a successful
     * response to a request that carried a real list, never for one that left the key out.
     *
     * <p>Called at most once per join call, on whatever thread completed it; implementations must not
     * block. The player may not be online yet (the blocking login path completes before the join).
     *
     * @param currentGroups exactly the list the request carried; never {@code null}
     */
    void groupsDelivered(UUID playerUuid, List<String> currentGroups);

    /**
     * Says that a join-time call carrying {@code currentGroups} is about to read them, and returns
     * a stamp to hand back with {@link #groupsDelivered(UUID, List, long)} when it succeeds.
     *
     * <p>Taken before the groups are read, because what it guards against is an answer that took
     * longer than a live report: a request reads the groups, a rank changes and is reported to the
     * bot in a frame, and then the request's answer arrives. Recording the request's older list on
     * its success would overwrite what the frame told the bot, and the next change would be
     * compared against the wrong thing. The stamp lets the implementation see that a frame went out
     * for the player after the request started, and keep that frame's record.
     *
     * <p>The default stamps nothing ({@link #UNSTAMPED}). Must not block: it is called on the
     * login path.
     */
    default long groupsRequestStarting(UUID playerUuid) {
        return UNSTAMPED;
    }

    /**
     * {@link #groupsDelivered(UUID, List)} for a request stamped by
     * {@link #groupsRequestStarting}. The default ignores the stamp.
     *
     * @param requestStamp what {@link #groupsRequestStarting} returned for this request
     */
    default void groupsDelivered(UUID playerUuid, List<String> currentGroups, long requestStamp) {
        groupsDelivered(playerUuid, currentGroups);
    }
}
