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

    /** Show them a six-digit code. May be a denial (pending auth) or an allow with an offer to link. */
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
