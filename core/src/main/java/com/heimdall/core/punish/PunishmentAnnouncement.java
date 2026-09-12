package com.heimdall.core.punish;

import com.heimdall.core.text.Msg;
import com.heimdall.core.text.Template;
import net.kyori.adventure.text.Component;

/**
 * The one chat line a punishment produces, and who is allowed to see it.
 *
 * <h2>A template, like the screens</h2>
 *
 * <p>The wording used to be assembled here out of §-codes, which meant a guild could rewrite
 * every word a player reads on a disconnect screen and not one word of what the server is told
 * about it. Both now come from the same place: a MiniMessage template with the same tokens, the
 * same optional-segment rule, and {@code {verb}} on top. A guild with no reason on a ban gets
 * "Adam banned Lerndmina" and nothing dangling, because the "for {reason}" clause is a segment
 * rather than a branch in this file.
 *
 * <p>What did <em>not</em> become configurable is the audience. Silence is a permission
 * question, and a template that could opt out of the {@code (silent)} prefix would be a way to
 * announce a silent punishment loudly.
 *
 * <h2>What it never contains</h2>
 *
 * <p>No address, no digest, no UUID. An IP ban announces as "IP-banned", and the address it was
 * computed from stays where it already lives, behind {@code /iphistory} and its own permission.
 * The line is also single-line by construction: {@link PunishmentText#sanitise} folds any
 * newline in a reason to a space rather than trusting it to be one line.
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
    public static final String CONSOLE = PunishmentText.CONSOLE;

    /**
     * Prepended to a silent line, ahead of whatever the template says.
     *
     * <p>Not part of the template, and not removable from one: see the class javadoc.
     */
    private static final String SILENT_PREFIX = "<dark_gray>(silent)</dark_gray> ";

    private final Component message;
    private final boolean silent;

    private PunishmentAnnouncement(Component message, boolean silent) {
        this.message = message;
        this.silent = silent;
    }

    /**
     * The line for a punishment that was just issued, or {@code null} if there is nothing to say.
     *
     * <p>{@code null} rather than an empty line for three cases that are not an announcement: a
     * type nobody is announced for ({@code geo}, {@code subnet}, {@code freeze} - none of them
     * name a player), a missing target name (announcing a UUID would be worse than staying
     * quiet), and a guild that cleared the template, which is how a guild turns announcements
     * off.
     *
     * @param template the guild's {@code announceIssue} text
     * @param view the punishment, whose {@link PunishmentView#silent()} decides the audience
     * @param nowMillis the clock, for the remaining-time token
     */
    public static PunishmentAnnouncement issued(String template, PunishmentView view,
            long nowMillis) {
        if (view == null) {
            return null;
        }
        String verb = PunishmentText.issueVerb(view.type(), view.permanent());
        return build(template, verb, view, nowMillis);
    }

    /**
     * The line for a punishment that was just revoked, or {@code null} if there is nothing to
     * say.
     *
     * <p>Takes either spelling of the action, because the two callers have different ones in
     * hand: a moderator typed {@code unban}, while a {@code punish.revoke} frame from the bot
     * names the punishment type ({@code ban}) that is being lifted. One table, so the two cannot
     * word the same event differently.
     */
    public static PunishmentAnnouncement revoked(String template, String typeOrVerb,
            PunishmentView view, long nowMillis) {
        if (view == null) {
            return null;
        }
        return build(template, PunishmentText.revokeVerb(typeOrVerb), view, nowMillis);
    }

    /**
     * The finished line, or {@code null} for the three cases that are not an announcement.
     *
     * <p><strong>A template that fills to nothing turns the line off for everybody, holders of
     * {@link #NOTIFY_PERMISSION} included, and that is the switch rather than a gap in one.</strong>
     * An empty template can only be a guild clearing the box on the dashboard: the bot sends its
     * default whenever the key is unset, and the plugin falls back to the same default whenever
     * the bot sends no key at all, so "" never arrives by omission. A guild that has said it wants
     * no broadcast has said so about the broadcast, not about who reads it - silencing the server
     * at large while still telling staff is what {@code -s} is for, and it is a per-punishment
     * decision rather than a permanent one.
     */
    private static PunishmentAnnouncement build(String template, String verb, PunishmentView view,
            long nowMillis) {
        if (verb == null) {
            return null;
        }
        if (PunishmentText.sanitise(view.targetName()).isEmpty()) {
            return null;
        }
        String filled = Template.fill(template, view.tokens(nowMillis).put("verb", verb));
        if (filled.trim().isEmpty()) {
            return null;
        }
        String body = view.silent() ? SILENT_PREFIX + filled : filled;
        return new PunishmentAnnouncement(Msg.mini(body), view.silent());
    }

    /** The finished line, parsed once, ready to send. */
    public Component message() {
        return message;
    }

    /**
     * The §-coded form, for a send path that predates Adventure.
     *
     * <p>Every platform Heimdall runs on takes a {@code Component}, so nothing in the plugin
     * needs this - it exists for the log line and for a test that wants to read the codes.
     */
    public String line() {
        return Msg.toLegacy(message);
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

    @Override
    public String toString() {
        return "PunishmentAnnouncement{" + (silent ? "silent" : "public") + ", " + line() + "}";
    }
}
