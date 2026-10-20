package com.heimdall.core.platform;

import com.heimdall.core.util.Registration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reading and writing a player's LuckPerms groups, without core knowing what LuckPerms is.
 *
 * <h2>Only managed groups are ever touched</h2>
 *
 * <p>{@link #setPlayerGroups} takes a <em>managed</em> list as well as a target list, and the two
 * are not interchangeable. The managed list is the set of groups the dashboard is allowed to have
 * an opinion about; everything else a player holds — a rank they bought, a staff group, a group
 * another plugin owns — is out of scope and must survive a sync untouched.
 *
 * <p>Concretely: a group is removed only if it is currently held, is in the managed list, and is
 * not in the target list. A group is added only if it is in the target list, is in the managed
 * list, and is not already held. An empty managed list means the bot has not told this server what
 * it owns, and the correct response is to change nothing at all — not to interpret it as "manage
 * everything", which would strip every group on the server.
 *
 * <h2>Availability is asked, not assumed</h2>
 *
 * <p>LuckPerms registers its service on its own schedule and there is no load-order guarantee, so
 * an implementation that resolved once at construction and cached a failure would leave role sync
 * dead for the lifetime of the process — issue #796 / MC-10, which is exactly what v2's Bukkit
 * implementation did. Every method here re-resolves, so a server where LuckPerms started second
 * heals itself.
 *
 * <h2>Threading</h2>
 *
 * <p>Every method returns a future and none of them blocks the caller. The blocking that LuckPerms
 * itself requires — loading a user from storage, saving one back — happens on {@code heimdall-io},
 * never on a server thread and never on the socket's reading thread.
 */
public interface LuckPermsBridge {

    /** Whether LuckPerms is present and its API has been resolved. Re-checked on every call. */
    boolean isAvailable();

    /**
     * The groups a player currently owns, including ones inherited through other groups and ones
     * granted only in another server's context.
     *
     * <p>Falls back to loading the user from storage when they are not in LuckPerms' cache — which
     * is the normal state during a pre-login check, since the player is not on the server yet.
     * Reporting an empty list there is issue #796 / MC-11: the bot then diffs against nothing and
     * concludes every managed group needs adding.
     *
     * <p><strong>Unknown is a failed future, never an empty list.</strong> An empty list means "holds
     * no groups" and the bot acts on it; LuckPerms being absent, a user that could not be loaded,
     * and a read that threw are all "unknown", and callers leave {@code currentGroups} out of their
     * requests for those.
     *
     * @return the group names, or a future failed with the reason they are not known
     */
    CompletableFuture<List<String>> getPlayerGroups(UUID playerUuid);

    /**
     * Applies the bot's group snapshot, touching only what {@code managedGroups} covers.
     *
     * <p>A group in {@code targetGroups} that does not exist in LuckPerms is skipped with a
     * warning rather than created: inventing a group because the dashboard named one that was
     * deleted would produce a permission set nobody configured.
     *
     * <p>The user is saved before the future completes, so a caller that waits for it can rely on
     * the change having reached storage rather than only the in-memory model.
     *
     * @param targetGroups the groups the player should hold; {@code null} is treated as empty
     * @param managedGroups the groups the dashboard owns; {@code null} or empty means change nothing
     * @return {@code true} if the sync ran to completion, {@code false} if it was skipped or failed
     */
    CompletableFuture<Boolean> setPlayerGroups(
            UUID playerUuid, List<String> targetGroups, List<String> managedGroups);

    /**
     * Subscribes to changes in any user's inheritance groups, for reverse role sync.
     *
     * <p>Fires after a group node is added to, removed from or cleared on a user, and after LuckPerms
     * recalculates a user's data. As far as the 5.4 API documents, that recalculation is the likeliest
     * signal for changes no node event describes (an expiring temporary rank, an edit to a group the
     * user inherits), but the API does not promise it, so nothing here depends on it.
     *
     * <p>The live report is the fast path, not the guaranteed one. Anything it misses (an expiry, a
     * change made on another server of a network sharing storage) is reconciled from the
     * {@code currentGroups} the join-time calls carry. Each call says whether it came from a node
     * event, a node event caused by this bridge's own {@link #setPlayerGroups}, or a bare
     * recalculation ({@link GroupsChangedListener.Cause}), because LuckPerms recalculates every user
     * it loads, and only a node event somebody else caused says a rank was edited.
     *
     * <p>Abstract rather than a default returning {@link Registration#NONE}, on purpose. A bridge
     * that silently never reports would make reverse sync look configured and do nothing, which is
     * the failure nobody notices; an implementation that genuinely cannot listen says so by writing
     * the {@code NONE} itself.
     *
     * <p>Subscribing when LuckPerms is not available returns {@link Registration#NONE} rather than
     * failing, and does <strong>not</strong> retry later. A caller that wants to survive LuckPerms
     * starting second re-subscribes once {@link #isAvailable()} turns true.
     *
     * @return a handle that unsubscribes; idempotent
     */
    Registration onGroupsChanged(GroupsChangedListener listener);

    /**
     * Identifies the LuckPerms instance this bridge can reach right now, so a subscriber can tell
     * that it was replaced underneath it.
     *
     * <p>A subscription made through {@link #onGroupsChanged} is bound to the event bus of the
     * instance that was live when it was made. If the instance is replaced, the old bus dies with it
     * and the subscription goes quiet without any error. A subscriber compares this with the value
     * it saw when it subscribed, and closes the old registration and subscribes again when they
     * differ. Compared by identity; {@code null} while LuckPerms is not available.
     *
     * <p><strong>What this does not cover: a PlugMan-style reload of LuckPerms itself.</strong> On
     * Bukkit, Heimdall's classes were linked against the LuckPerms API classes of the plugin
     * classloader LuckPerms had when Heimdall first touched them, {@code LuckPermsProvider} included.
     * A reloaded LuckPerms gets a new classloader with its own copy of those classes and registers
     * itself in its own {@code LuckPermsProvider}, while the old one, the one Heimdall can see, was
     * emptied when the old instance shut down. So after such a reload this answers {@code null}, the
     * bridge reports LuckPerms unavailable, and role sync stays off until Heimdall is reloaded as well
     * or the server restarts. Nothing here tries to reach across classloaders to the new instance:
     * the result would be a second, unsupported way of linking against LuckPerms, built for one
     * kind of plugin reload. What the comparison does catch is any replacement
     * visible through the provider Heimdall is linked against, at the cost of two field reads.
     *
     * <p>The default is this bridge itself, which never changes: a bridge that cannot tell is never
     * resubscribed, which is the behaviour before this existed.
     */
    default Object instanceToken() {
        return this;
    }
}
