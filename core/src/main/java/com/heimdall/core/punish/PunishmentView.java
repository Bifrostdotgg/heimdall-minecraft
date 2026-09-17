package com.heimdall.core.punish;

import com.heimdall.core.text.Template;

/**
 * One punishment, as the screens and the announcements need to read it.
 *
 * <p>Primitives rather than the module's mirror row, for the reason the rest of {@code core} is
 * shaped this way: the mirror belongs to {@code :module-punishments}, the rendering rules belong
 * here, and a core class that imported a module type would invert the dependency the whole build
 * is arranged around. The module fills one of these in from its row; a test fills one in from
 * nothing.
 *
 * <p>Immutable, and safe to build on any thread.
 */
public final class PunishmentView {

    private final String type;
    private final String targetName;
    private final String staffName;
    private final String id;
    private final String reason;
    private final String serverName;
    private final String appealUrl;
    private final long issuedAtMillis;
    private final Long expiresAtMillis;
    private final Long lengthSeconds;
    private final boolean silent;
    private final boolean hidden;

    private PunishmentView(Builder builder) {
        this.type = builder.type == null ? "" : builder.type;
        this.targetName = builder.targetName == null ? "" : builder.targetName;
        this.staffName = builder.staffName == null ? "" : builder.staffName;
        this.id = shareableId(builder.id);
        this.reason = builder.reason == null ? "" : builder.reason;
        this.serverName = builder.serverName == null ? "" : builder.serverName;
        this.appealUrl = builder.appealUrl == null ? "" : builder.appealUrl;
        this.issuedAtMillis = builder.issuedAtMillis;
        this.expiresAtMillis = builder.expiresAtMillis;
        this.lengthSeconds = builder.lengthSeconds;
        this.silent = builder.silent;
        this.hidden = builder.hidden;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String type() {
        return type;
    }

    public String targetName() {
        return targetName;
    }

    /** The raw issuer name. Empty means the console; {@link PunishmentText#issuer} decides. */
    public String staffName() {
        return staffName;
    }

    /** The id to show, or {@code ""} when there is nothing worth showing. See {@link #LOCAL_ID}. */
    public String id() {
        return id;
    }

    /**
     * The prefix the punishments module gives a row it filed without the bot.
     *
     * <p>A punishment issued while the bot is unreachable is written to the local mirror
     * immediately, under an id this server invented so that the row has a key. It is replaced by
     * the real one as soon as the queued write is acknowledged.
     */
    private static final String LOCAL_ID = "local-";

    /**
     * The id a player can act on, or {@code ""} for one only this server has ever seen.
     *
     * <p>The screen's ID row exists so a player can quote it on an appeal. A {@code local-<uuid>}
     * is the opposite of that: nobody the player can reach has ever heard of it, and asking them
     * to write it down is asking for a number that will be wrong by the time anyone reads it.
     * Resolving it to empty drops the row through the optional-segment rule for the length of the
     * outage, and the real id brings the row back when the write syncs.
     */
    private static String shareableId(String value) {
        if (value == null || value.isEmpty() || value.startsWith(LOCAL_ID)) {
            return "";
        }
        return value;
    }

    public String reason() {
        return reason;
    }

    public String serverName() {
        return serverName;
    }

    public String appealUrl() {
        return appealUrl;
    }

    /** When it was issued, or {@code 0} when this instance was never told. */
    public long issuedAtMillis() {
        return issuedAtMillis;
    }

    /** When it ends, or {@code null} for a punishment that does not. */
    public Long expiresAtMillis() {
        return expiresAtMillis;
    }

    public boolean silent() {
        return silent;
    }

    /**
     * Whether the row is hidden, in the {@link HiddenPunishments} sense.
     *
     * <p>Carried on the view rather than passed beside it because the announcement for a revoke
     * has to read it off the row being lifted: {@code -p} on a hidden ban must not publish the
     * lift to a server that was never told about the ban.
     */
    public boolean hidden() {
        return hidden;
    }

    /** Whether it never ends. Drives the type label, the verb and the permanent screen variant. */
    public boolean permanent() {
        return expiresAtMillis == null;
    }

    /**
     * How long it was set for, in seconds, or {@code null} when it is permanent.
     *
     * <p>The bot sends the length on a row that has one. When it does not - an older bot, a
     * mirror file written before the field existed - it is derived from the two instants, which
     * is the same number unless the row was edited after the fact. With neither, the length is
     * unknown and renders as nothing rather than as a guess: the {@code {duration}} segment
     * disappears and the screen still says when it expires.
     */
    public Long lengthSeconds() {
        if (expiresAtMillis == null) {
            return null;
        }
        if (lengthSeconds != null && lengthSeconds.longValue() > 0L) {
            return lengthSeconds;
        }
        if (issuedAtMillis <= 0L) {
            return null;
        }
        long span = (expiresAtMillis.longValue() - issuedAtMillis) / 1000L;
        return span <= 0L ? null : Long.valueOf(span);
    }

    /** How long is left, in seconds, or {@code null} when it is permanent or already over. */
    public Long remainingSeconds(long nowMillis) {
        if (expiresAtMillis == null) {
            return null;
        }
        long left = (expiresAtMillis.longValue() - nowMillis) / 1000L;
        return left <= 0L ? null : Long.valueOf(left);
    }

    /**
     * Every token a screen or an announcement can use, except {@code {base}} and {@code {verb}}.
     *
     * <p>Those two are the caller's: {@code {base}} is rendered template output and has to be
     * inserted raw, and {@code {verb}} only means anything in an announcement.
     *
     * <p>Player-supplied values are sanitised here as well as escaped by the template engine.
     * The escape is what stops a reason introducing MiniMessage tags; the sanitiser is what stops
     * it introducing §-codes, which survive the escape and are interpreted by the client at the
     * far end of the Bukkit family's legacy serialisation.
     */
    public Template.Values tokens(long nowMillis) {
        String duration = PunishmentText.compactDuration(lengthSeconds());
        String remaining = PunishmentText.compactDuration(remainingSeconds(nowMillis));
        return Template.values()
                .put("server", PunishmentText.sanitise(serverName))
                .put("player", PunishmentText.sanitise(targetName))
                .put("staff", PunishmentText.issuer(staffName))
                .put("id", id)
                .put("type", PunishmentText.typeLabel(type, permanent()))
                .put("reason", PunishmentText.sanitise(reason))
                .put("duration", duration == null ? "" : duration)
                .put("remaining", remaining == null ? "" : remaining)
                .put("date_start", PunishmentText.stamp(issuedAtMillis))
                .put("date_end", expiresAtMillis == null
                        ? "" : PunishmentText.stamp(expiresAtMillis.longValue()))
                .put("appeal_url", appealUrl);
    }

    /** The mutable writer. Every field is optional; an absent one renders as empty. */
    public static final class Builder {

        private String type;
        private String targetName;
        private String staffName;
        private String id;
        private String reason;
        private String serverName;
        private String appealUrl;
        private long issuedAtMillis;
        private Long expiresAtMillis;
        private Long lengthSeconds;
        private boolean silent;
        private boolean hidden;

        private Builder() {
        }

        public Builder type(String value) {
            this.type = value;
            return this;
        }

        public Builder targetName(String value) {
            this.targetName = value;
            return this;
        }

        public Builder staffName(String value) {
            this.staffName = value;
            return this;
        }

        public Builder id(String value) {
            this.id = value;
            return this;
        }

        public Builder reason(String value) {
            this.reason = value;
            return this;
        }

        public Builder serverName(String value) {
            this.serverName = value;
            return this;
        }

        public Builder appealUrl(String value) {
            this.appealUrl = value;
            return this;
        }

        public Builder issuedAtMillis(long value) {
            this.issuedAtMillis = value;
            return this;
        }

        public Builder expiresAtMillis(Long value) {
            this.expiresAtMillis = value;
            return this;
        }

        /** The length the bot recorded, in seconds. Left unset, it is derived from the dates. */
        public Builder lengthSeconds(Long value) {
            this.lengthSeconds = value;
            return this;
        }

        public Builder silent(boolean value) {
            this.silent = value;
            return this;
        }

        /** Hidden implies silent; {@link PunishmentAnnouncement} applies that, not this builder. */
        public Builder hidden(boolean value) {
            this.hidden = value;
            return this;
        }

        public PunishmentView build() {
            return new PunishmentView(this);
        }
    }
}
