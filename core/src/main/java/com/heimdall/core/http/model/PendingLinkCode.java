package com.heimdall.core.http.model;

import com.heimdall.core.util.Strings;
import java.util.Objects;

/**
 * The answer to {@code POST /pending-link-code}: the code a link already started from Discord is
 * waiting on, or nothing.
 *
 * <p>Unlike {@link LinkCodeResult} this never describes a request it made. The bot mints the code
 * when the player starts linking in Discord; this endpoint only reports it, so asking on every join
 * cannot create anything on the bot.
 *
 * <p>"Pending" always carries a code. A {@code pending: true} with no code is read as
 * {@link #none()}, because there is nothing to show a player and the caller would otherwise have to
 * re-check for it at every use.
 */
public final class PendingLinkCode {

    private static final PendingLinkCode NONE = new PendingLinkCode(null, null);

    private final String code;
    private final String expiresAt;

    private PendingLinkCode(String code, String expiresAt) {
        this.code = code;
        this.expiresAt = expiresAt;
    }

    /** Nothing is pending for this player. */
    public static PendingLinkCode none() {
        return NONE;
    }

    /**
     * A code is waiting to be entered in Discord.
     *
     * @param code the six-digit code; blank makes this {@link #none()}
     * @param expiresAt when it expires, ISO-8601 as the bot wrote it, or {@code null} if it did not
     *     say. Kept as text: parsing is the caller's, which knows what an unreadable value costs.
     */
    public static PendingLinkCode of(String code, String expiresAt) {
        if (Strings.isBlank(code)) {
            return NONE;
        }
        return new PendingLinkCode(code.trim(), Strings.trimToNull(expiresAt));
    }

    /** Whether there is a code to show. */
    public boolean pending() {
        return code != null;
    }

    /** The code, or {@code null} when nothing is {@link #pending()}. */
    public String code() {
        return code;
    }

    /** The expiry exactly as the bot sent it, or {@code null}. */
    public String expiresAt() {
        return expiresAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PendingLinkCode)) {
            return false;
        }
        PendingLinkCode that = (PendingLinkCode) other;
        return Objects.equals(code, that.code) && Objects.equals(expiresAt, that.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, expiresAt);
    }

    @Override
    public String toString() {
        return pending()
                ? "PendingLinkCode{code='" + code + "', expiresAt='" + expiresAt + "'}"
                : "PendingLinkCode{none}";
    }
}
