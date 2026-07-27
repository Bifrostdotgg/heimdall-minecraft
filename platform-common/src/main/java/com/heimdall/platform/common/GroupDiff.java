package com.heimdall.platform.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a role sync should change, and — much more importantly — what it must leave alone.
 *
 * <p>Pulled out of the LuckPerms bridge as a pure function of three lists, because this is the part
 * that can be wrong in a way nobody notices for a week. The plumbing around it (resolve the API,
 * load the user, save them back) fails loudly; getting the <em>diff</em> wrong silently strips a
 * rank somebody paid for.
 *
 * <p>The rules, and the reason each one exists:
 *
 * <ul>
 *   <li><strong>Only managed groups are touched.</strong> A player's staff group, their donor rank,
 *       a group another plugin owns — none of those are the dashboard's business, and a sync that
 *       reconciled the full set would delete them.
 *   <li><strong>An empty managed list means change nothing.</strong> Not "manage everything". The
 *       empty list is what arrives when the bot has not told this server what it owns yet, and
 *       reading it as full authority would strip every group on the server on the first sync after
 *       a deploy.
 *   <li><strong>Remove: held, managed, not wanted. Add: wanted, managed, not held.</strong> Both
 *       conditions are needed on both sides — a target group outside the managed set is a group the
 *       dashboard is asking for but does not own, and granting it would let a misconfigured
 *       dashboard hand out permissions nobody agreed it could.
 * </ul>
 *
 * <p>Comparison is case-sensitive, matching LuckPerms itself: group names there are already
 * normalised to lower case at creation, so lowering them again here would only mask a caller
 * sending something LuckPerms would never have stored.
 *
 * <p>Immutable, and both lists preserve their input order so a log line reads the way an operator
 * expects.
 */
public final class GroupDiff {

    private static final GroupDiff EMPTY =
            new GroupDiff(Collections.<String>emptyList(), Collections.<String>emptyList());

    private final List<String> toAdd;
    private final List<String> toRemove;

    private GroupDiff(List<String> toAdd, List<String> toRemove) {
        this.toAdd = Collections.unmodifiableList(toAdd);
        this.toRemove = Collections.unmodifiableList(toRemove);
    }

    /**
     * Works out the change.
     *
     * @param current the groups the player holds now; {@code null} is treated as empty
     * @param target the groups the dashboard says they should hold; {@code null} is treated as empty
     * @param managed the groups the dashboard owns; {@code null} or empty means change nothing
     */
    public static GroupDiff compute(
            Collection<String> current, Collection<String> target, Collection<String> managed) {
        if (managed == null || managed.isEmpty()) {
            return EMPTY;
        }
        Set<String> managedSet = new HashSet<String>(managed);
        Set<String> currentSet = current == null
                ? Collections.<String>emptySet()
                : new HashSet<String>(current);
        Set<String> targetSet = target == null
                ? Collections.<String>emptySet()
                : new HashSet<String>(target);

        List<String> remove = new ArrayList<String>();
        if (current != null) {
            for (String group : current) {
                if (managedSet.contains(group) && !targetSet.contains(group)) {
                    remove.add(group);
                }
            }
        }

        List<String> add = new ArrayList<String>();
        if (target != null) {
            for (String group : target) {
                if (managedSet.contains(group) && !currentSet.contains(group)) {
                    add.add(group);
                }
            }
        }
        return new GroupDiff(add, remove);
    }

    /** Groups to grant, in the order the dashboard listed them. */
    public List<String> toAdd() {
        return toAdd;
    }

    /** Groups to revoke, in the order the player holds them. */
    public List<String> toRemove() {
        return toRemove;
    }

    /** Whether there is anything to do. A no-change sync must not write the user back. */
    public boolean isEmpty() {
        return toAdd.isEmpty() && toRemove.isEmpty();
    }

    @Override
    public String toString() {
        return "GroupDiff{add=" + toAdd + ", remove=" + toRemove + "}";
    }
}
