package com.heimdall.core.admin;

import com.heimdall.core.util.Strings;

/**
 * What {@code /hd test <player>} learned, without a player having tried to join.
 *
 * <p>v2's {@code /hwl test} made a bare {@code connection-attempt} call and printed the four fields
 * it parsed out of the answer. This is the same idea taken one step further: the probe runs the
 * <em>whole login interceptor</em> — the module toggle, the role check, the bypass list, the mirror,
 * the bot, and the fallback mode — so what it reports is the decision that player would actually
 * get, not the decision the bot alone would suggest. The two differ exactly where support
 * conversations are hardest: a bypassed UUID, a backend with {@code enforceOnBackend} off, a warm
 * mirror during an outage.
 *
 * <p>{@link #stage()} is the part v2 had no equivalent of and the part worth having. "Denied" is
 * rarely the interesting answer; "denied by the fallback mode because the bot is unreachable and the
 * mirror does not hold them" is.
 *
 * <p><strong>Nothing is written.</strong> The probe deliberately does not record the player in the
 * mirror and does not fire the background connection-attempt report a real mirror hit would, because
 * an operator testing whether somebody <em>can</em> join must not thereby cache them as somebody who
 * did. That is the one behavioural difference from the real path, and it is why the interceptor has
 * a probe mode rather than the command constructing a synthetic login event.
 *
 * <p>Immutable.
 */
public final class LoginProbe {

    private final String username;
    private final String uuid;
    private final boolean allowed;
    private final String stage;
    private final String message;
    private final Integer queuePosition;
    private final boolean mirrored;

    private LoginProbe(Builder builder) {
        this.username = Strings.trimToEmpty(builder.username);
        this.uuid = Strings.trimToEmpty(builder.uuid);
        this.allowed = builder.allowed;
        this.stage = Strings.trimToEmpty(builder.stage);
        this.message = Strings.trimToEmpty(builder.message);
        this.queuePosition = builder.queuePosition;
        this.mirrored = builder.mirrored;
    }

    public static Builder forPlayer(String username, String uuid) {
        return new Builder().username(username).uuid(uuid);
    }

    /** The name that was probed, as typed. */
    public String username() {
        return username;
    }

    /**
     * The UUID the probe ran against.
     *
     * <p>The player's real one when they are online. When they are not, it is the offline-mode UUID
     * derived from their name, which is the right answer on a cracked server and the wrong one on a
     * premium server — so the command says which it used rather than quietly presenting a guess as
     * fact.
     */
    public String uuid() {
        return uuid;
    }

    /** Whether this player would be let in. */
    public boolean allowed() {
        return allowed;
    }

    /** Which check decided, in words: {@code mirror hit}, {@code bot: revoked}, {@code bypassed}, … */
    public String stage() {
        return stage;
    }

    /** The kick message they would see, or {@code ""} when they would be admitted. */
    public String message() {
        return message;
    }

    /** Their place in the approval queue, or {@code null} — absent and zero are different (D1). */
    public Integer queuePosition() {
        return queuePosition;
    }

    /** Whether the local whitelist mirror currently holds this player. */
    public boolean mirrored() {
        return mirrored;
    }

    @Override
    public String toString() {
        return "LoginProbe{" + username + " -> " + (allowed ? "allow" : "deny")
                + " at " + stage + "}";
    }

    /** The mutable writer. */
    public static final class Builder {

        private String username;
        private String uuid;
        private boolean allowed;
        private String stage;
        private String message;
        private Integer queuePosition;
        private boolean mirrored;

        private Builder() {
        }

        public Builder username(String value) {
            this.username = value;
            return this;
        }

        public Builder uuid(String value) {
            this.uuid = value;
            return this;
        }

        public Builder allowed(boolean value) {
            this.allowed = value;
            return this;
        }

        public Builder stage(String value) {
            this.stage = value;
            return this;
        }

        public Builder message(String value) {
            this.message = value;
            return this;
        }

        public Builder queuePosition(Integer value) {
            this.queuePosition = value;
            return this;
        }

        public Builder mirrored(boolean value) {
            this.mirrored = value;
            return this;
        }

        public LoginProbe build() {
            return new LoginProbe(this);
        }
    }
}
