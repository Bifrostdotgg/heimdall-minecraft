package com.heimdall.core.punish;

/**
 * The punishment screens and announcement lines a guild gets when it has not written its own.
 *
 * <p><strong>Byte-identical to {@code DEFAULT_PUNISHMENT_SCREENS} in
 * {@code packages/shared/minecraftPunishmentScreens.ts}.</strong> The bot stores a guild's screens
 * and pushes them in the punishments module settings, but it only pushes the keys that exist, and
 * a plugin talking to an older bot is sent none of them at all. Both sides therefore need the same
 * defaults, and two copies that drift are a screen that looks different depending on which half of
 * the deploy went out first.
 *
 * <p>An explicitly present empty string is not a missing key: it means "render nothing". An empty
 * base renders nothing at the top of every screen, and an empty permanent variant means the
 * temporary screen is used for a permanent punishment as well - which is what the defaults do,
 * because the optional-segment rule already removes the Length line when there is no length.
 *
 * <p>See {@link com.heimdall.core.text.Template} for the segment and token rules these are
 * written in, and the Minecraft page of the dashboard for where a guild edits them.
 */
public final class PunishmentScreens {

    private PunishmentScreens() {
    }

    /**
     * The block every other screen starts with. Empty renders nothing.
     *
     * <p>The ID row is an optional segment, and that is not cosmetic. A punishment issued while
     * the bot is unreachable is filed locally under an invented {@code local-<uuid>} id, and
     * {@link PunishmentView} resolves one of those to nothing: the row therefore disappears for
     * the length of the outage and comes back with the real id once the write syncs. An id that
     * only this server has ever seen is not something an appeal can be opened with, and printing
     * it asks the player to quote a number nobody can look up.
     */
    public static final String BASE =
            "<b><gradient:#c278b0:#e08888:#e8be7e:#a0cc7c:#7cbec8:#a292dc>"
            + "{server}</gradient></b> <gray>Punishments</gray>\n"
            + "<gradient:#c278b0:#a292dc><st>                                          "
            + "</st></gradient>\n"
            + "[<gray>Staff</gray> <dark_gray>»</dark_gray> <yellow>{staff}</yellow>]\n"
            + "<gray>You</gray> <dark_gray>»</dark_gray> <yellow>{player}</yellow>\n"
            + "[<gray>ID</gray> <dark_gray>»</dark_gray> <yellow>{id}</yellow>]";

    /** The disconnect screen for a ban, an IP ban, a country ban or a subnet ban. */
    public static final String BAN =
            "{base}\n"
            + "<gray>Punishment</gray> <dark_gray>»</dark_gray> <red>{type}</red>\n"
            + "[<gray>Reason</gray> <dark_gray>»</dark_gray> <yellow>{reason}</yellow>"
            + "]\n"
            + "<gray>Issued</gray> <dark_gray>»</dark_gray> <yellow>"
            + "{date_start}</yellow>\n"
            + "[<gray>Length</gray> <dark_gray>»</dark_gray> <yellow>"
            + "{duration}</yellow> <gray>({remaining} remaining)</gray>]\n"
            + "<gradient:#c278b0:#a292dc><st>                                          "
            + "</st></gradient>\n"
            + "[<gray>You can appeal at</gray> <aqua>{appeal_url}</aqua><gray>, "
            + "include a screenshot of this whole screen.</gray>]";

    /** The permanent variant. Empty means reuse {@link #BAN}, as the drop rule handles it. */
    public static final String BAN_PERMANENT = "";

    /** The chat message a muted player gets when they try to talk. */
    public static final String MUTE =
            "{base}\n"
            + "<gray>Punishment</gray> <dark_gray>»</dark_gray> <red>{type}</red>\n"
            + "[<gray>Reason</gray> <dark_gray>»</dark_gray> <yellow>{reason}</yellow>"
            + "]\n"
            + "[<gray>Expires</gray> <dark_gray>»</dark_gray> <yellow>"
            + "{date_end}</yellow> <gray>({remaining} remaining)</gray>]\n"
            + "[<gray>You can appeal at</gray> <aqua><click:open_url:'{appeal_url}'>"
            + "{appeal_url}</click></aqua>]\n"
            + "<gradient:#c278b0:#a292dc><st>                                          "
            + "</st></gradient>";

    /** The permanent variant. Empty means reuse {@link #MUTE}. */
    public static final String MUTE_PERMANENT = "";

    /** The disconnect screen for a kick. */
    public static final String KICK =
            "{base}\n"
            + "<gray>Punishment</gray> <dark_gray>»</dark_gray> <red>{type}</red>\n"
            + "[<gray>Reason</gray> <dark_gray>»</dark_gray> <yellow>{reason}</yellow>"
            + "]\n"
            + "<gradient:#c278b0:#a292dc><st>                                          "
            + "</st></gradient>";

    /** The chat message a warning is delivered as. */
    public static final String WARN =
            "{base}\n"
            + "<gray>Punishment</gray> <dark_gray>»</dark_gray> <red>{type}</red>\n"
            + "[<gray>Reason</gray> <dark_gray>»</dark_gray> <yellow>{reason}</yellow>"
            + "]\n"
            + "[<gray>You can appeal at</gray> <aqua><click:open_url:'{appeal_url}'>"
            + "{appeal_url}</click></aqua>]\n"
            + "<gradient:#c278b0:#a292dc><st>                                          "
            + "</st></gradient>";

    /** The one line a new punishment is broadcast as. */
    public static final String ANNOUNCE_ISSUE =
            "<b><gradient:#c278b0:#a292dc>{server}</gradient></b> <dark_gray>"
            + "»</dark_gray> <white>{staff}</white> <gray>{verb}</gray> <white>"
            + "{player}</white>[ <gray>for</gray> <white>{reason}</white>][ <dark_gray>"
            + "({duration})</dark_gray>]";

    /** The one line a lifted punishment is broadcast as. */
    public static final String ANNOUNCE_REVOKE =
            "<b><gradient:#c278b0:#a292dc>{server}</gradient></b> <dark_gray>"
            + "»</dark_gray> <white>{staff}</white> <gray>{verb}</gray> <white>"
            + "{player}</white>[ <gray>for</gray> <white>{reason}</white>]";

    /**
     * The settings key each segment arrives under, flat in the punishments module's settings.
     *
     * <p>Named here as well as in the TypeScript so a rename shows up as a compile error on the
     * reader rather than as a screen that silently reverted to its default.
     */
    public static final String KEY_BASE = "screenBase";

    public static final String KEY_BAN = "banScreen";

    public static final String KEY_BAN_PERMANENT = "banPermanentScreen";

    public static final String KEY_MUTE = "muteScreen";

    public static final String KEY_MUTE_PERMANENT = "mutePermanentScreen";

    public static final String KEY_KICK = "kickScreen";

    public static final String KEY_WARN = "warnScreen";

    public static final String KEY_ANNOUNCE_ISSUE = "announceIssue";

    public static final String KEY_ANNOUNCE_REVOKE = "announceRevoke";

    /** The guild's name, shown as the wordmark at the top of every screen. */
    public static final String KEY_SERVER_NAME = "serverName";
}
