package com.heimdall.core.http.model;

import java.util.Objects;

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

    private LinkCodeResult(boolean alreadyLinked, String code, Builder builder) {
        this.alreadyLinked = alreadyLinked;
        this.code = code;
        this.message = builder == null ? null : builder.message;
        this.discordId = builder == null ? null : builder.discordId;
        this.discordUsername = builder == null ? null : builder.discordUsername;
        this.discordDisplayName = builder == null ? null : builder.discordDisplayName;
    }

    /** A newly minted six-digit code. */
    public static LinkCodeResult code(String code) {
        return new LinkCodeResult(false, code, null);
    }

    /**
     * Already linked: no code, but the Discord account it is linked to.
     *
     * <p>A builder rather than four positional Strings, all of which are nullable and none of which
     * the compiler can tell apart. Named {@code linkedTo} rather than {@code alreadyLinked} so it
     * does not collide with the accessor of the same name.
     */
    public static Builder linkedTo() {
        return new Builder();
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
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LinkCodeResult)) {
            return false;
        }
        LinkCodeResult that = (LinkCodeResult) other;
        return alreadyLinked == that.alreadyLinked
                && Objects.equals(code, that.code)
                && Objects.equals(message, that.message)
                && Objects.equals(discordId, that.discordId)
                && Objects.equals(discordUsername, that.discordUsername)
                && Objects.equals(discordDisplayName, that.discordDisplayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Boolean.valueOf(alreadyLinked), code, message, discordId, discordUsername,
                discordDisplayName);
    }

    @Override
    public String toString() {
        return alreadyLinked
                ? "LinkCodeResult{alreadyLinked, discordId='" + discordId + "'}"
                : "LinkCodeResult{code='" + code + "'}";
    }

    /** Mutable writer for the already-linked shape. */
    public static final class Builder {

        private String message;
        private String discordId;
        private String discordUsername;
        private String discordDisplayName;

        private Builder() {
        }

        public Builder message(String value) {
            this.message = value;
            return this;
        }

        public Builder discordId(String value) {
            this.discordId = value;
            return this;
        }

        public Builder discordUsername(String value) {
            this.discordUsername = value;
            return this;
        }

        public Builder discordDisplayName(String value) {
            this.discordDisplayName = value;
            return this;
        }

        public LinkCodeResult build() {
            return new LinkCodeResult(true, null, this);
        }
    }
}
