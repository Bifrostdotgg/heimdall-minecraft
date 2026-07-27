package com.heimdall.core.http.model;

/**
 * The bot's verdict on a connection attempt.
 *
 * <p>{@link #action()} is derived, not received — see {@link ConnectionAction}. The raw flags that
 * produced it are kept only where a module genuinely needs them: {@link #revoked()} distinguishes
 * "was whitelisted, no longer is" from "never was", which is a different message to show.
 *
 * <p><strong>{@link #queuePosition()} is a nullable Integer and that is deliberate.</strong> The bot
 * has two pending-approval branches. Staff approval computes a position and sends it; scheduled
 * auto-whitelist omits the key entirely ({@code ...(queuePosition !== null && { queuePosition })}).
 * A primitive {@code int} defaulting to 0 makes the second branch indistinguishable from "you are
 * position zero", which is what v2 shipped.
 */
public final class ConnectionAttemptResult {

    private final boolean whitelisted;
    private final ConnectionAction action;
    private final String message;
    private final String authCode;
    private final Integer queuePosition;
    private final boolean revoked;
    private final RoleSyncDirective roleSync;

    private ConnectionAttemptResult(Builder builder) {
        this.whitelisted = builder.whitelisted;
        this.action = builder.action == null ? ConnectionAction.DENY : builder.action;
        this.message = builder.message;
        this.authCode = builder.authCode;
        this.queuePosition = builder.queuePosition;
        this.revoked = builder.revoked;
        this.roleSync = builder.roleSync == null ? RoleSyncDirective.absent() : builder.roleSync;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Whether the bot says this player belongs on the whitelist. */
    public boolean whitelisted() {
        return whitelisted;
    }

    /** What to do about it. Never {@code null}. */
    public ConnectionAction action() {
        return action;
    }

    /**
     * The message to show the player, already colour-coded by the bot.
     *
     * <p>{@code null} for a plain {@link ConnectionAction#ALLOW} — there is nothing to say to a
     * player who is simply let in, and v2 nulled it for exactly that reason.
     */
    public String message() {
        return message;
    }

    /** The six-digit linking code, or {@code null} when the response carried none. */
    public String authCode() {
        return authCode;
    }

    /** Position in the staff-approval queue, or {@code null} on the scheduled branch. */
    public Integer queuePosition() {
        return queuePosition;
    }

    /** Whether a queue position was sent at all. */
    public boolean hasQueuePosition() {
        return queuePosition != null;
    }

    /** Whether this player's whitelist was revoked, as opposed to never granted. */
    public boolean revoked() {
        return revoked;
    }

    /** What to do about permission groups. Never {@code null}; see {@link RoleSyncDirective}. */
    public RoleSyncDirective roleSync() {
        return roleSync;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("ConnectionAttemptResult{action=")
                .append(action.wireName())
                .append(", whitelisted=")
                .append(whitelisted);
        if (revoked) {
            out.append(", revoked=true");
        }
        if (authCode != null) {
            out.append(", authCode='").append(authCode).append('\'');
        }
        if (queuePosition != null) {
            out.append(", queuePosition=").append(queuePosition);
        }
        if (roleSync.isPresent()) {
            out.append(", ").append(roleSync);
        }
        return out.append('}').toString();
    }

    /** Mutable writer used by the response parser. */
    public static final class Builder {

        private boolean whitelisted;
        private ConnectionAction action;
        private String message;
        private String authCode;
        private Integer queuePosition;
        private boolean revoked;
        private RoleSyncDirective roleSync;

        private Builder() {
        }

        public Builder whitelisted(boolean value) {
            this.whitelisted = value;
            return this;
        }

        public Builder action(ConnectionAction value) {
            this.action = value;
            return this;
        }

        public Builder message(String value) {
            this.message = value;
            return this;
        }

        public Builder authCode(String value) {
            this.authCode = value;
            return this;
        }

        /** {@code null} means the key was absent, which is not the same as zero. */
        public Builder queuePosition(Integer value) {
            this.queuePosition = value;
            return this;
        }

        public Builder revoked(boolean value) {
            this.revoked = value;
            return this;
        }

        public Builder roleSync(RoleSyncDirective value) {
            this.roleSync = value;
            return this;
        }

        public ConnectionAttemptResult build() {
            return new ConnectionAttemptResult(this);
        }
    }
}
