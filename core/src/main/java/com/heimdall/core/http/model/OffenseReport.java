package com.heimdall.core.http.model;

import java.util.Objects;

import com.heimdall.core.util.Strings;
import java.util.Locale;

/** One offense being reported against a player by a staff member. */
public final class OffenseReport {

    private final String targetUuid;
    private final String targetUsername;
    private final String offenseSlug;
    private final String issuedByUuid;
    private final String issuedByUsername;
    private final String notes;

    private OffenseReport(Builder builder) {
        if (Strings.isBlank(builder.targetUuid)) {
            throw new IllegalArgumentException("targetUuid is required");
        }
        if (Strings.isBlank(builder.targetUsername)) {
            throw new IllegalArgumentException("targetUsername is required");
        }
        if (Strings.isBlank(builder.offenseSlug)) {
            throw new IllegalArgumentException("offenseSlug is required");
        }
        this.targetUuid = builder.targetUuid.trim();
        this.targetUsername = builder.targetUsername.trim();
        this.offenseSlug = builder.offenseSlug.trim().toLowerCase(Locale.ROOT);
        this.issuedByUuid = Strings.trimToNull(builder.issuedByUuid);
        this.issuedByUsername = Strings.trimToNull(builder.issuedByUsername);
        this.notes = Strings.trimToNull(builder.notes);
    }

    public static Builder builder(String targetUuid, String targetUsername, String offenseSlug) {
        return new Builder()
                .targetUuid(targetUuid)
                .targetUsername(targetUsername)
                .offenseSlug(offenseSlug);
    }

    /**
     * The offender's UUID, sent exactly as given.
     *
     * <p>Not case-folded: the bot counts prior infractions with a Mongo equality match, which is
     * case-sensitive, so normalising here would merge two of its running totals into one and hide
     * an escalation bug.
     */
    public String targetUuid() {
        return targetUuid;
    }

    public String targetUsername() {
        return targetUsername;
    }

    /** Lower-cased, matching v2 and the bot's own lookup. */
    public String offenseSlug() {
        return offenseSlug;
    }

    /** The reporting staff member's UUID, or {@code null} when issued from the console. */
    public String issuedByUuid() {
        return issuedByUuid;
    }

    /** The reporting staff member's name, or {@code null}. */
    public String issuedByUsername() {
        return issuedByUsername;
    }

    /** Free-text notes, or {@code null}. */
    public String notes() {
        return notes;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OffenseReport)) {
            return false;
        }
        OffenseReport that = (OffenseReport) other;
        return targetUuid.equals(that.targetUuid)
                && targetUsername.equals(that.targetUsername)
                && offenseSlug.equals(that.offenseSlug)
                && Objects.equals(issuedByUuid, that.issuedByUuid)
                && Objects.equals(issuedByUsername, that.issuedByUsername)
                && Objects.equals(notes, that.notes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(targetUuid, targetUsername, offenseSlug, issuedByUuid, issuedByUsername,
                notes);
    }

    @Override
    public String toString() {
        return "OffenseReport{" + offenseSlug + " against " + targetUsername + "}";
    }

    /** Mutable writer. The three target fields are mandatory. */
    public static final class Builder {

        private String targetUuid;
        private String targetUsername;
        private String offenseSlug;
        private String issuedByUuid;
        private String issuedByUsername;
        private String notes;

        private Builder() {
        }

        public Builder targetUuid(String value) {
            this.targetUuid = value;
            return this;
        }

        public Builder targetUsername(String value) {
            this.targetUsername = value;
            return this;
        }

        public Builder offenseSlug(String value) {
            this.offenseSlug = value;
            return this;
        }

        public Builder issuedBy(String uuid, String username) {
            this.issuedByUuid = uuid;
            this.issuedByUsername = username;
            return this;
        }

        public Builder notes(String value) {
            this.notes = value;
            return this;
        }

        public OffenseReport build() {
            return new OffenseReport(this);
        }
    }
}
