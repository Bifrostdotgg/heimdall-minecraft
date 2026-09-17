package com.heimdall.core.punish;

/**
 * The one node that governs hidden punishments, and the one sentence a refusal says.
 *
 * <p>A hidden punishment is enforced exactly like any other - the target still meets the ban or
 * mute screen - but it is kept out of in-game staff lookups. Holding this node does two things,
 * deliberately together: it lets a sender issue one with {@code -h}, and it lets them see the ones
 * that exist. Splitting them would produce a moderator who can hide a punishment and then cannot
 * find it again.
 *
 * <p>It is <strong>not</strong> implied by {@link PunishmentAnnouncement#ADMIN_PERMISSION} and it
 * is not a child of it in the Bukkit descriptor. Hiding a moderation record from other staff is a
 * stronger act than administering the plugin, and a server granting the admin node has not thereby
 * decided who may keep secrets from its own team.
 *
 * <p>Hidden implies silent, and the hidden node alone is enough: a sender permitted to hide a
 * punishment does not additionally need {@link SilenceDecision#OVERRIDE_PERMISSION} to make the
 * implied silence happen. The reverse is not true - the silence override grants nothing here.
 */
public final class HiddenPunishments {

    /** Issue hidden punishments with {@code -h}, and see hidden rows in lookups. Default: op. */
    public static final String PERMISSION = "heimdall.punishments.hidden";

    /** What a sender without the node is told. One sentence, naming the thing they may not do. */
    public static final String REFUSAL_MESSAGE = "You may not issue hidden punishments.";

    private HiddenPunishments() {
    }
}
