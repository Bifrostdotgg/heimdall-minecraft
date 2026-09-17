package com.heimdall.core.punish;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one chat line a punishment produces, and who is allowed to see it.
 *
 * <p>Everything here is a string transformation over primitives. That is deliberate: the audience
 * rule and the wording are the parts that would otherwise only be exercised by starting a server,
 * and they are also the parts a mistake is least visible in - an announcement that reaches one
 * person too many is not an error anything logs.
 *
 * <h2>What it never contains</h2>
 *
 * <p>No address, no digest, no UUID. An IP ban announces as "IP-banned", and the address it was
 * computed from stays where it already lives, behind {@code /iphistory} and its own permission.
 * The line is also single-line by construction: a reason arrives from a moderator or from the
 * dashboard, so any newline in it is folded to a space rather than trusted to be one line.
 *
 * <h2>Silence is about the audience, not the text</h2>
 *
 * <p>A silent punishment produces the same sentence with a {@code (silent)} prefix, shown only to
 * holders of {@link #NOTIFY_PERMISSION} (or {@code heimdall.admin}). Staff therefore always learn
 * that an action happened; what silence buys is that the server at large does not. A silent
 * punishment that produced no line at all would make "silent" mean "unaudited in chat", which is
 * not what a moderator asking for it wants.
 *
 * <h2>Hidden is a narrower audience, not a louder silence</h2>
 *
 * <p>A hidden punishment is hidden <em>from staff</em>: it is kept out of the in-game lookups for
 * everybody without {@link HiddenPunishments#PERMISSION}. Silent alone would therefore leak the
 * whole thing in one line, because the silent audience is the notify node, which is exactly the
 * staff a hidden row is being kept from. So a hidden line goes to holders of the hidden node and
 * to nobody else - not to notify holders, not to {@code heimdall.admin} - and carries a
 * {@code (hidden)} prefix instead of {@code (silent)}, matching the marker the lookups put on the
 * same row. It is still announced to that audience for the reason silent lines are: an action
 * nobody can read about in chat is unaudited, and the people who may read this one are named.
 */
public final class PunishmentAnnouncement {

    /** Sees silent punishment announcements. Default: op only. */
    public static final String NOTIFY_PERMISSION = "heimdall.punishments.notify";

    /** Holders see everything the notify node sees, as they do everywhere else in the plugin. */
    public static final String ADMIN_PERMISSION = "heimdall.admin";

    /** What an issuer with no recorded name is called. */
    public static final String CONSOLE = "Console";

    /** {@code §#rrggbb}, the serializer's own hex spelling. Stripped before the pair form. */
    private static final Pattern HEX_CODE = Pattern.compile("[\u00A7&]#[0-9A-Fa-f]{6}");

    /** One legacy code: a colour, a format, reset, or the {@code x} that opens the hex run. */
    private static final Pattern LEGACY_CODE = Pattern.compile("[\u00A7&][0-9A-Fa-fK-Ok-oRrXx]");

    /**
     * A MiniMessage-shaped tag. Anchored on a letter, a slash or a bang after the {@code <} so an
     * ordinary {@code <3} or {@code a < b} in a reason survives being written by a human.
     */
    private static final Pattern TAG = Pattern.compile("<[A-Za-z/!][^<>]*>");

    /**
     * How many times {@link #clean} repeats before giving up.
     *
     * <p>Generous: each pass strips at least one character or stops, so anything a human types
     * settles in two or three. A reason long enough to need more is adversarial, and by then the
     * §-codes and tags left in it have been thinned that many times over.
     */
    private static final int MAX_CLEAN_PASSES = 8;

    private static final int MINUTES_PER_HOUR = 60;
    private static final int MINUTES_PER_DAY = 60 * 24;

    private final String line;
    private final boolean silent;
    private final boolean hidden;

    private PunishmentAnnouncement(String line, boolean silent, boolean hidden) {
        this.line = line;
        this.silent = silent;
        this.hidden = hidden;
    }

    /**
     * The line for a punishment that was just issued, or {@code null} if there is nothing to say.
     *
     * <p>{@code null} rather than an empty line for the two cases that are not an announcement:
     * a type nobody is announced for ({@code geo}, {@code subnet}, {@code freeze} - none of them
     * name a player, and a country-wide ban is not a moderation event a server should read as one),
     * and a missing target name. Announcing a UUID would be worse than staying quiet.
     *
     * @param type the punishment type - {@code ban}, {@code tempban}, {@code ipban}, {@code mute},
     *     {@code tempmute}, {@code kick} or {@code warn}
     * @param staff who issued it; blank means the console
     * @param target the punished player's name
     * @param durationMinutes how long it lasts, or {@code null} for permanent
     * @param reason free text from the issuer, possibly empty
     * @param silent whether only notify holders see it
     */
    public static PunishmentAnnouncement issued(String type, String staff, String target,
            Integer durationMinutes, String reason, boolean silent) {
        return issued(type, staff, target, durationMinutes, reason, silent, false);
    }

    /**
     * The same line, for a punishment that may be hidden.
     *
     * <p>{@code hidden} implies silence and overrides it: the prefix becomes {@code (hidden)} and
     * the audience narrows to the hidden node alone. See the class javadoc for why the notify
     * audience is the wrong one for a row that is being kept from staff.
     */
    public static PunishmentAnnouncement issued(String type, String staff, String target,
            Integer durationMinutes, String reason, boolean silent, boolean hidden) {
        String verb = issueVerb(type);
        if (verb == null) {
            return null;
        }
        String who = clean(target);
        if (who.isEmpty()) {
            return null;
        }
        StringBuilder body = new StringBuilder();
        body.append("§f").append(issuer(staff)).append(" §c").append(verb).append(" §f").append(who);
        String duration = compactDuration(durationMinutes);
        if (duration != null) {
            body.append(" §7for §f").append(duration);
        }
        appendReason(body, reason);
        return new PunishmentAnnouncement(
                prefixed(body.toString(), silent || hidden, hidden), silent || hidden, hidden);
    }

    /**
     * The line for a punishment that was just revoked, or {@code null} if there is nothing to say.
     *
     * <p>Takes either spelling of the action, because the two callers have different ones in hand:
     * a moderator typed {@code unban}, while a {@code punish.revoke} frame from the bot names the
     * punishment type ({@code ban}) that is being lifted. One table, so the two cannot word the
     * same event differently.
     */
    public static PunishmentAnnouncement revoked(String typeOrVerb, String staff, String target,
            String reason, boolean silent) {
        return revoked(typeOrVerb, staff, target, reason, silent, false);
    }

    /**
     * The same line, for the lifting of a punishment that was hidden.
     *
     * <p>Read off the row being lifted, never off a flag: {@code -p} on a hidden ban would
     * otherwise publish "Adam unbanned Steve" to a server that was never told about the ban, which
     * discloses the punishment, later and out of context, to everybody.
     */
    public static PunishmentAnnouncement revoked(String typeOrVerb, String staff, String target,
            String reason, boolean silent, boolean hidden) {
        String verb = revokeVerb(typeOrVerb);
        if (verb == null) {
            return null;
        }
        String who = clean(target);
        if (who.isEmpty()) {
            return null;
        }
        StringBuilder body = new StringBuilder();
        body.append("§f").append(issuer(staff)).append(" §a").append(verb).append(" §f").append(who);
        appendReason(body, reason);
        return new PunishmentAnnouncement(
                prefixed(body.toString(), silent || hidden, hidden), silent || hidden, hidden);
    }

    /** The finished legacy-§ line, ready for {@code Msg.legacy}. */
    public String line() {
        return line;
    }

    /** Whether this goes only to notify holders. Always true when {@link #hidden()} is. */
    public boolean silent() {
        return silent;
    }

    /** Whether this goes only to holders of {@link HiddenPunishments#PERMISSION}. */
    public boolean hidden() {
        return hidden;
    }

    /**
     * Whether one player sees this line.
     *
     * <p>Takes the two answers rather than a player, so the rule stays testable without a server
     * and so the caller does the permission lookups on whichever thread it is already on.
     */
    public boolean visibleTo(boolean hasNotify, boolean hasAdmin) {
        return visibleTo(hasNotify, hasAdmin, false);
    }

    /**
     * Whether one player sees this line, including the hidden case.
     *
     * <p>A hidden line ignores the other two answers entirely. The notify node is the audience a
     * hidden row is being kept from, and {@code heimdall.admin} deliberately does not imply the
     * hidden node, so neither may widen this. The two-argument form above is kept for callers with
     * no hidden lookup in hand, and it answers false for a hidden line - the safe direction, since
     * a caller that has not been taught about hidden cannot be trusted to narrow the audience.
     */
    public boolean visibleTo(boolean hasNotify, boolean hasAdmin, boolean hasHidden) {
        if (hidden) {
            return hasHidden;
        }
        return !silent || hasNotify || hasAdmin;
    }

    /**
     * A duration a human reads at a glance, or {@code null} for permanent.
     *
     * <p>Two units at most. "3d 4h" is the answer to "how long"; "3d 4h 17m" is an answer to a
     * question nobody asked in a broadcast line.
     */
    public static String compactDuration(Integer minutes) {
        if (minutes == null || minutes.intValue() <= 0) {
            return null;
        }
        int total = minutes.intValue();
        int days = total / MINUTES_PER_DAY;
        int hours = (total % MINUTES_PER_DAY) / MINUTES_PER_HOUR;
        int mins = total % MINUTES_PER_HOUR;
        if (days > 0) {
            return hours > 0 ? days + "d " + hours + "h" : days + "d";
        }
        if (hours > 0) {
            return mins > 0 ? hours + "h " + mins + "m" : hours + "h";
        }
        return mins + "m";
    }

    private static void appendReason(StringBuilder body, String reason) {
        String text = clean(reason);
        if (!text.isEmpty()) {
            body.append(" §7: §f").append(text);
        }
    }

    private static String prefixed(String body, boolean silent, boolean hidden) {
        if (hidden) {
            // One prefix, not two: the audience is the hidden node alone, so "(silent)" beside it
            // would name a wider audience than the line actually has.
            return "§5(hidden) " + body;
        }
        return silent ? "§8(silent) " + body : body;
    }

    private static String issuer(String staff) {
        String name = clean(staff);
        if (name.isEmpty() || "console".equals(name.toLowerCase(Locale.ROOT))) {
            return CONSOLE;
        }
        return name;
    }

    /**
     * Makes one user-controlled segment safe to drop into a formatted line.
     *
     * <p>Three things a reason, a target name or an issuer name must not be able to do.
     *
     * <p><strong>Colour.</strong> The line is assembled as legacy §-coded text and parsed by
     * {@code Msg.legacy}, so a reason of {@code §r§8(silent) §fNotch} would reset the formatting
     * and print a convincing forgery of a silent announcement about somebody else. Both the § and
     * the & spellings go, along with the {@code §x§f§f…} and {@code §#ffffff} hex forms, and any
     * § left over afterwards.
     *
     * <p><strong>Tags.</strong> {@code Msg.legacy} does not parse MiniMessage, so {@code <red>}
     * renders literally today - but the punishment screens next door are MiniMessage, one
     * {@code Msg.miniTemplate} call away, and a segment that is only safe because of which parser
     * happens to be on the other end is not safe. Stripped here instead.
     *
     * <p><strong>Line breaks.</strong> Folded to spaces, so one punishment stays one line.
     *
     * <h2>Run to a fixpoint, not once</h2>
     *
     * <p>One pass in a fixed order composes into an escape. {@code <§4red>} is not a tag while the
     * §4 is in it, so the tag pass leaves it; the legacy pass then removes the §4 and hands back a
     * live {@code <red>} that nothing looks at again. {@code <&4red>} is the same trick in the
     * other spelling, and the two passes can be arranged into that shape whichever order they run
     * in, because each one's output is the other one's input.
     *
     * <p>So the passes repeat until the string stops changing. {@link #MAX_CLEAN_PASSES} bounds it
     * rather than trusting the loop to converge: every pass only deletes, so a string of length n
     * settles in at most n rounds and the cap is never the thing that ends it, but a reason
     * arrives from a moderator and an unbounded loop over user input is not something to leave to
     * a proof.
     */
    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace('\n', ' ').replace('\r', ' ');
        for (int pass = 0; pass < MAX_CLEAN_PASSES; pass++) {
            String before = text;
            text = TAG.matcher(text).replaceAll("");
            text = HEX_CODE.matcher(text).replaceAll("");
            text = LEGACY_CODE.matcher(text).replaceAll("");
            if (text.equals(before)) {
                break;
            }
        }
        return text.replace("\u00A7", "").trim();
    }

    private static String issueVerb(String type) {
        if (type == null) {
            return null;
        }
        String key = type.toLowerCase(Locale.ROOT);
        if ("ban".equals(key) || "tempban".equals(key)) return "banned";
        if ("ipban".equals(key)) return "IP-banned";
        if ("mute".equals(key) || "tempmute".equals(key)) return "muted";
        if ("kick".equals(key)) return "kicked";
        if ("warn".equals(key)) return "warned";
        return null;
    }

    private static String revokeVerb(String typeOrVerb) {
        if (typeOrVerb == null) {
            return null;
        }
        String key = typeOrVerb.toLowerCase(Locale.ROOT);
        if ("ban".equals(key) || "tempban".equals(key) || "ipban".equals(key)
                || "unban".equals(key)) {
            return "unbanned";
        }
        if ("mute".equals(key) || "tempmute".equals(key) || "unmute".equals(key)) {
            return "unmuted";
        }
        if ("warn".equals(key) || "unwarn".equals(key)) {
            return "unwarned";
        }
        if ("rollback".equals(key)) {
            return "revoked a punishment for";
        }
        return null;
    }

    @Override
    public String toString() {
        String audience = hidden ? "hidden" : (silent ? "silent" : "public");
        return "PunishmentAnnouncement{" + audience + ", " + line + "}";
    }
}
