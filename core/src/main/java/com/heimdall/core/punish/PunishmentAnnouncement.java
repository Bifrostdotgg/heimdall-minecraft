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

    private static final int SECONDS_PER_MINUTE = 60;
    private static final int SECONDS_PER_HOUR = 60 * 60;
    private static final int SECONDS_PER_DAY = 24 * 60 * 60;

    private final String line;
    private final boolean silent;

    private PunishmentAnnouncement(String line, boolean silent) {
        this.line = line;
        this.silent = silent;
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
     * @param durationSeconds how long it lasts, in seconds, or {@code null} for permanent
     * @param reason free text from the issuer, possibly empty
     * @param silent whether only notify holders see it
     */
    public static PunishmentAnnouncement issued(String type, String staff, String target,
            Integer durationSeconds, String reason, boolean silent) {
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
        String duration = compactDuration(durationSeconds);
        if (duration != null) {
            body.append(" §7for §f").append(duration);
        }
        appendReason(body, reason);
        return new PunishmentAnnouncement(prefixed(body.toString(), silent), silent);
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
        return new PunishmentAnnouncement(prefixed(body.toString(), silent), silent);
    }

    /** The finished legacy-§ line, ready for {@code Msg.legacy}. */
    public String line() {
        return line;
    }

    /** Whether this goes only to notify holders. */
    public boolean silent() {
        return silent;
    }

    /**
     * Whether one player sees this line.
     *
     * <p>Takes the two answers rather than a player, so the rule stays testable without a server
     * and so the caller does the permission lookups on whichever thread it is already on.
     */
    public boolean visibleTo(boolean hasNotify, boolean hasAdmin) {
        return !silent || hasNotify || hasAdmin;
    }

    /**
     * A duration a human reads at a glance, or {@code null} for permanent.
     *
     * <p>Two units at most. "3d 4h" is the answer to "how long"; "3d 4h 17m" is an answer to a
     * question nobody asked in a broadcast line.
     *
     * <p>Seconds in, and seconds out when that is all there is: a 30 second mute reads as
     * {@code 30s} rather than being rounded up to the minute the whole pipeline used to store.
     * The same two-unit shape as {@code formatDuration} in {@code packages/shared}, so the screen
     * a player sees and the dashboard row a moderator reads say the same thing.
     */
    public static String compactDuration(Integer seconds) {
        if (seconds == null || seconds.intValue() <= 0) {
            return null;
        }
        int total = seconds.intValue();
        int days = total / SECONDS_PER_DAY;
        int hours = (total % SECONDS_PER_DAY) / SECONDS_PER_HOUR;
        int minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        int rest = total % SECONDS_PER_MINUTE;
        if (days > 0) {
            return hours > 0 ? days + "d " + hours + "h" : days + "d";
        }
        if (hours > 0) {
            return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        }
        if (minutes > 0) {
            return rest > 0 ? minutes + "m " + rest + "s" : minutes + "m";
        }
        return rest + "s";
    }

    private static void appendReason(StringBuilder body, String reason) {
        String text = clean(reason);
        if (!text.isEmpty()) {
            body.append(" §7: §f").append(text);
        }
    }

    private static String prefixed(String body, boolean silent) {
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
        return "PunishmentAnnouncement{" + (silent ? "silent" : "public") + ", " + line + "}";
    }
}
