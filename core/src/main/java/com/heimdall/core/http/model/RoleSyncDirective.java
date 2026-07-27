package com.heimdall.core.http.model;

import com.heimdall.core.util.Lists;
import java.util.List;

/**
 * What the bot wants done about this player's permission groups — as a tri-state, because the wire
 * is one.
 *
 * <p>{@code roleSync} arrives in three distinct shapes and they mean three different things:
 *
 * <ul>
 *   <li><strong>absent or {@code null}</strong> — no snapshot exists for this player yet. Change
 *       nothing. ({@link #absent()})
 *   <li><strong>{@code {enabled: false}}</strong> — the bot is driving LuckPerms itself, over RCON.
 *       Explicitly keep out. ({@link #disabled()})
 *   <li><strong>{@code {enabled: true, targetGroups, managedGroups}}</strong> — apply this.
 *       ({@link #enabled(List, List)})
 * </ul>
 *
 * <p>Collapsing the first two into one "not enabled" boolean is exactly how a plugin ends up
 * fighting the bot for control of a group, so {@link #isPresent()} and {@link #isEnabled()} are
 * kept separate.
 */
public final class RoleSyncDirective {

    private static final RoleSyncDirective ABSENT = new RoleSyncDirective(false, false, null, null);
    private static final RoleSyncDirective DISABLED = new RoleSyncDirective(true, false, null, null);

    private final boolean present;
    private final boolean enabled;
    private final List<String> targetGroups;
    private final List<String> managedGroups;

    private RoleSyncDirective(
            boolean present, boolean enabled, List<String> targetGroups, List<String> managedGroups) {
        this.present = present;
        this.enabled = enabled;
        this.targetGroups = Lists.copyOf(targetGroups);
        this.managedGroups = Lists.copyOf(managedGroups);
    }

    /** No {@code roleSync} object on the wire: the player has no snapshot. Change nothing. */
    public static RoleSyncDirective absent() {
        return ABSENT;
    }

    /** {@code {enabled: false}}: the bot owns groups for this guild. Change nothing. */
    public static RoleSyncDirective disabled() {
        return DISABLED;
    }

    /** {@code {enabled: true, …}}: apply these groups. */
    public static RoleSyncDirective enabled(List<String> targetGroups, List<String> managedGroups) {
        return new RoleSyncDirective(true, true, targetGroups, managedGroups);
    }

    /** Whether the bot sent a {@code roleSync} object at all. */
    public boolean isPresent() {
        return present;
    }

    /** Whether the plugin should apply {@link #targetGroups()}. Implies {@link #isPresent()}. */
    public boolean isEnabled() {
        return enabled;
    }

    /** The groups the player should end up in. Empty unless {@link #isEnabled()}. */
    public List<String> targetGroups() {
        return targetGroups;
    }

    /**
     * The groups Heimdall considers its own to add and remove.
     *
     * <p>Anything outside this set is somebody else's and must be left alone. Empty unless {@link
     * #isEnabled()}.
     */
    public List<String> managedGroups() {
        return managedGroups;
    }

    @Override
    public String toString() {
        if (!present) {
            return "RoleSync{absent}";
        }
        if (!enabled) {
            return "RoleSync{disabled}";
        }
        return "RoleSync{target=" + targetGroups + ", managed=" + managedGroups + "}";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RoleSyncDirective)) {
            return false;
        }
        RoleSyncDirective that = (RoleSyncDirective) other;
        return present == that.present
                && enabled == that.enabled
                && targetGroups.equals(that.targetGroups)
                && managedGroups.equals(that.managedGroups);
    }

    @Override
    public int hashCode() {
        int result = (present ? 1 : 0);
        result = 31 * result + (enabled ? 1 : 0);
        result = 31 * result + targetGroups.hashCode();
        result = 31 * result + managedGroups.hashCode();
        return result;
    }
}
