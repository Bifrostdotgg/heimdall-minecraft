package com.heimdall.core.http.model;

/**
 * The answer to {@code POST /request-link-code}: either a fresh code, or a report that this
 * Minecraft account is already linked to a Discord one.
 *
 * <p><strong>Deliberate change from v2:</strong> v2 threw a {@code RuntimeException} carrying the
 * "already linked to …" sentence when {@code alreadyLinked} was true. That turned an ordinary,
 * expected answer into an exception, discarded the structured Discord fields, and left the command
 * handler string-matching an exception message to tell it apart from a real failure. Here it is
 * data, and only transport and HTTP failures are exceptional.
 */
public final class LinkCodeResult {

    private final boolean alreadyLinked;
    private final String code;
    private final String message;
    private final String discordId;
    private final String discordUsername;
    private final String discordDisplayName;

    private LinkCodeResult(
            boolean alreadyLinked,
            String code,
            String message,
            String discordId,
            String discordUsername,
            String discordDisplayName) {
        this.alreadyLinked = alreadyLinked;
        this.code = code;
        this.message = message;
        this.discordId = discordId;
        this.discordUsername = discordUsername;
        this.discordDisplayName = discordDisplayName;
    }

    /** A newly minted six-digit code. */
    public static LinkCodeResult code(String code) {
        return new LinkCodeResult(false, code, null, null, null, null);
    }

    /** Already linked: no code, but the Discord account it is linked to. */
    public static LinkCodeResult alreadyLinked(
            String message, String discordId, String discordUsername, String discordDisplayName) {
        return new LinkCodeResult(true, null, message, discordId, discordUsername, discordDisplayName);
    }

    /** Whether the account is already linked, in which case {@link #code()} is {@code null}. */
    public boolean alreadyLinked() {
        return alreadyLinked;
    }

    /** The six-digit code, or {@code null} when {@link #alreadyLinked()}. */
    public String code() {
        return code;
    }

    /** The bot's phrasing of the already-linked message, or {@code null}. */
    public String message() {
        return message;
    }

    /** The linked Discord user's snowflake, or {@code null}. */
    public String discordId() {
        return discordId;
    }

    /** The linked Discord user's handle, or {@code null}. */
    public String discordUsername() {
        return discordUsername;
    }

    /** The linked Discord user's display name, or {@code null}. */
    public String discordDisplayName() {
        return discordDisplayName;
    }

    @Override
    public String toString() {
        return alreadyLinked
                ? "LinkCodeResult{alreadyLinked, discordId='" + discordId + "'}"
                : "LinkCodeResult{code='" + code + "'}";
    }
}
