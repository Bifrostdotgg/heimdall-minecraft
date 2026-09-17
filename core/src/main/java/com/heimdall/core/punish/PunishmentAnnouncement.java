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
    public static final String CONSOLE = PunishmentText.CONSOLE;

    /**
     * Prepended to a silent line, ahead of whatever the template says.
     *
     * <p>Not part of the template, and not removable from one: see the class javadoc.
     */
    private static final String SILENT_PREFIX = "<dark_gray>(silent)</dark_gray> ";

    /**
     * Prepended to a hidden line, in place of {@link #SILENT_PREFIX}.
     *
     * <p>One prefix, not two: the audience is the hidden node alone, so "(silent)" beside it
     * would name a wider audience than the line actually has. Also not part of the template, for
     * the same reason silence is not - a guild able to clear it could announce a hidden
     * punishment as an ordinary one.
     */
    private static final String HIDDEN_PREFIX = "<dark_purple>(hidden)</dark_purple> ";

    private final Component message;
    private final boolean silent;
    private final boolean hidden;

    private PunishmentAnnouncement(Component message, boolean silent, boolean hidden) {
        this.message = message;
        this.silent = silent;
        this.hidden = hidden;
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
     * @param view the punishment, whose {@link PunishmentView#silent()} and
     *     {@link PunishmentView#hidden()} decide the audience
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
        // Hidden overrides silent rather than adding to it, and is read off the view - which for
        // a revoke is the row being lifted - so -p cannot publish the lift of a hidden row.
        boolean hidden = view.hidden();
        boolean silent = view.silent() || hidden;
        String prefix = hidden ? HIDDEN_PREFIX : (silent ? SILENT_PREFIX : "");
        String body = prefix + filled;
        // The template, not the filled line, is what a parse failure is reported against: the
        // values change with every punishment, so keying on the finished text would report one
        // broken template once per ban and exhaust the warning budget within an evening.
        return new PunishmentAnnouncement(Msg.mini(body, template), silent, hidden);
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

    @Override
    public String toString() {
        String audience = hidden ? "hidden" : (silent ? "silent" : "public");
        return "PunishmentAnnouncement{" + audience + ", " + line() + "}";
    }
}
