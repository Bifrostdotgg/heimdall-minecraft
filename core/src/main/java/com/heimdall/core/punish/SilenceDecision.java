package com.heimdall.core.punish;

/**
 * Whether one punishment is silent, and whether the sender was allowed to say so.
 *
 * <p>The guild's {@code silentByDefault} setting is the default and needs no permission. Departing
 * from it in <em>either</em> direction - {@code -s} on a guild that announces, {@code -p} on a
 * guild that does not - is the privileged act, because both are a choice about who finds out that
 * a moderator acted. So one node, {@link #OVERRIDE_PERMISSION}, gates the departure rather than
 * gating the {@code -s} flag specifically.
 *
 * <p>A flag that agrees with the default changes nothing and is allowed: a moderator who types
 * {@code -s} on a guild that is already silent has not overridden anything, and refusing there
 * would be refusing a no-op.
 *
 * <p><strong>A refused override refuses the command.</strong> Ignoring the flag and punishing
 * anyway is the worse failure: the moderator believes the ban went out quietly, the server
 * announces it, and nobody learns the flag did nothing until it matters. The same reasoning runs
 * the other way for {@code -p}.
 *
 * <p>Pure and immutable: four booleans in, one answer out, no server required to test it.
 */
public final class SilenceDecision {

    /** Lets a sender override the guild default with {@code -s} or {@code -p}. Default: op only. */
    public static final String OVERRIDE_PERMISSION = "heimdall.punishments.silent";

    /**
     * Holders may override without the node above, as they may see silent announcements without
     * the notify node. One spelling, shared with {@link PunishmentAnnouncement#ADMIN_PERMISSION}.
     */
    public static final String ADMIN_PERMISSION = PunishmentAnnouncement.ADMIN_PERMISSION;

    /** What a refused sender is told. One sentence, and it names the thing they may not do. */
    public static final String REFUSAL_MESSAGE =
            "You are not allowed to change whether a punishment is silent.";

    private final boolean silent;
    private final boolean refused;

    private SilenceDecision(boolean silent, boolean refused) {
        this.silent = silent;
        this.refused = refused;
    }

    /**
     * Whether a sender may depart from the guild default.
     *
     * <p>{@code heimdall.admin} grants it, exactly as it grants
     * {@link PunishmentAnnouncement#NOTIFY_PERMISSION} in {@code visibleTo}. The Bukkit descriptor
     * declares both as {@code children} of the admin node, and the two have to agree: a
     * descriptor that says a permission is held while the code refuses it is worse than either
     * answer on its own, because {@code /lp user <name> permission check} then confirms something
     * the server will not do. The proxies have no descriptor at all, so on Velocity and BungeeCord
     * this method is the only place the implication exists.
     *
     * <p>Two booleans rather than a sender, for the reason {@link #decide} takes four: the caller
     * does the lookups on whatever thread it is already on, and this stays testable without a
     * server.
     */
    public static boolean mayOverride(boolean hasOverrideNode, boolean hasAdminNode) {
        return hasOverrideNode || hasAdminNode;
    }

    /**
     * Resolves the flags against the guild default.
     *
     * <p>{@code -s} wins over {@code -p} when a sender somehow passes both. That is the safe
     * direction: the narrower audience cannot leak something the wider one would have kept.
     *
     * @param silentFlag the sender passed {@code -s}
     * @param publicFlag the sender passed {@code -p}
     * @param silentByDefault the guild's {@code silentByDefault} setting
     * @param mayOverride the sender holds {@link #OVERRIDE_PERMISSION}
     */
    public static SilenceDecision decide(boolean silentFlag, boolean publicFlag,
            boolean silentByDefault, boolean mayOverride) {
        boolean wanted = silentFlag || (silentByDefault && !publicFlag);
        if (wanted != silentByDefault && !mayOverride) {
            return new SilenceDecision(silentByDefault, true);
        }
        return new SilenceDecision(wanted, false);
    }

    /**
     * Whether the command must not run.
     *
     * <p>{@link #silent()} still answers the guild default when this is true, so a caller that
     * forgets to check cannot accidentally invert the announcement - but it must check, because
     * the punishment itself is what is being refused.
     */
    public boolean refused() {
        return refused;
    }

    /** Whether the punishment, and its announcement, are silent. */
    public boolean silent() {
        return silent;
    }

    @Override
    public String toString() {
        return "SilenceDecision{" + (refused ? "refused" : silent ? "silent" : "public") + "}";
    }
}
