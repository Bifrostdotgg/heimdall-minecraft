package com.heimdall.core.http.model;

import java.util.Locale;

/**
 * What the plugin should do with a connecting player.
 *
 * <p>The bot does not send this. It sends a set of booleans, and the plugin derives the action from
 * them in a fixed order — see {@code ApiResponses.actionFor}. Deriving it once, here, is the point:
 * v2's login listener read {@code whitelisted}, {@code pendingAuth}, {@code pendingApproval} and
 * {@code existingPlayerLink} in each of two platform listeners, and the two got out of step.
 */
public enum ConnectionAction {

    /** Let them in, say nothing. */
    ALLOW,

    /**
     * Refuse them, and put a six-digit link code in the kick message.
     *
     * <p>Both shapes that reach this — {@code pendingAuth} and {@code existingPlayerLink} — are
     * refusals, even though the second carries {@code whitelisted: true}. An earlier version of this
     * comment called that one "an allow with an offer to link"; it is not, and v2 is the authority:
     * its login listener disallows on {@code show_auth_code} without distinguishing the two.
     *
     * <p>It could not sensibly work the other way. The code is delivered in the kick screen because
     * a player who is admitted has no reason to read chat, and admitting them would additionally
     * mean caching them — which is the lockout in issue #796 / MC-4, where the next attempt is a
     * mirror hit and the code is never shown again.
     */
    SHOW_AUTH_CODE,

    /** Linked, waiting on staff approval or a scheduled auto-whitelist. */
    PENDING_APPROVAL,

    /** Not whitelisted. */
    DENY;

    /** The lower-case spelling, matching v2's {@code action} strings. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
