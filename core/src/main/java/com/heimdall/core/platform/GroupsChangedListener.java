package com.heimdall.core.platform;

import java.util.List;
import java.util.UUID;

/**
 * Told when a player's LuckPerms inheritance groups may have changed.
 *
 * <p>Registered through {@link LuckPermsBridge#onGroupsChanged}. This is the in-game half of reverse
 * role sync: a rank bought in game has to reach Discord, and the plugin is the only side that sees
 * LuckPerms change.
 *
 * <h2>"May have changed", and the full list every time</h2>
 *
 * <p>The listener is handed the user's <em>whole</em> current group list, never a delta. LuckPerms
 * fires several events for one logical change (a node added, a node removed, then a data
 * recalculation), and it also recalculates for reasons that change nothing about groups at all. A
 * delta would make every consumer reassemble the state from a sequence it cannot be sure it saw
 * completely; a snapshot makes a duplicate or a spurious call harmless, because comparing a set with
 * an equal set is a no-op. So a listener must tolerate being called with a list identical to the
 * last one, and deciding whether anything actually changed is its job.
 *
 * <h2>Threading</h2>
 *
 * <p>Never called on a LuckPerms thread and never on a server thread. Implementations hop to their
 * own executor ({@code heimdall-io} in production) before reading the groups. Calls are coalesced per
 * user (several events for one user while a read is pending produce one call, reading the latest
 * state) and delivered one at a time, so for any one user a later call carries a state at least as
 * new as an earlier one. Nothing is promised about ordering between different users. A listener
 * that throws is logged and does not stop later calls.
 */
@FunctionalInterface
public interface GroupsChangedListener {

    /**
     * What made LuckPerms say the groups may have changed.
     *
     * <p>Carried because the two are not equally trustworthy as a signal for a player who is not
     * online. LuckPerms recalculates a user's data every time it loads one, which includes a login
     * that is still in progress, a lookup by another plugin and a storage sync, so a recalculation
     * for an offline user says nothing about whether anybody edited their ranks. A node event does:
     * somebody added, removed or cleared an inheritance node on that user, on this server.
     */
    enum Cause {

        /**
         * An inheritance node was added to, removed from or cleared on the user: a real rank edit,
         * on this server. With shared LuckPerms storage only the server that made the edit fires it.
         */
        INHERITANCE_NODE,

        /** LuckPerms recalculated the user's data, for any reason, including simply loading them. */
        RECALCULATION
    }

    /**
     * @param uuid the user whose groups may have changed; online or not
     * @param currentGroups every group the user currently inherits, including through other groups,
     *     in the same form {@link LuckPermsBridge#getPlayerGroups} reports; never {@code null}
     */
    void onGroupsChanged(UUID uuid, List<String> currentGroups);

    /**
     * The same call with what LuckPerms knows about the event. This is the overload a bridge
     * invokes; the default drops the extra arguments, so a listener that only cares about the list
     * implements the two-argument form and nothing else.
     *
     * <p>When several events for one user are coalesced into one call, {@code cause} is
     * {@link Cause#INHERITANCE_NODE} if any of them was a node event: a recalculation queued
     * behind a real edit must not hide the edit.
     *
     * @param username the name LuckPerms has stored for the user, or {@code null} if it has none
     * @param cause what triggered the call; see {@link Cause}
     */
    default void onGroupsChanged(
            UUID uuid, String username, List<String> currentGroups, Cause cause) {
        onGroupsChanged(uuid, currentGroups);
    }
}
