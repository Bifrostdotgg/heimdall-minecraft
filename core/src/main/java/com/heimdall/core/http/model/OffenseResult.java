package com.heimdall.core.http.model;

/**
 * What the bot decided about a reported offense: the escalation tier it resolved and the command
 * the server should run.
 *
 * <p>{@link #command()} is the bot's, not the plugin's. The escalation table, the point totals and
 * the placeholder substitution all live server-side; the plugin dispatches the string it is given.
 * That is what keeps a dashboard change effective immediately across a whole fleet.
 */
public final class OffenseResult {

    private final String infractionId;
    private final String command;
    private final String action;
    private final Integer durationMinutes;
    private final int totalPoints;
    private final int tierApplied;
    private final String tierDescription;
    private final String offenseType;

    private OffenseResult(Builder builder) {
        this.infractionId = builder.infractionId == null ? "" : builder.infractionId;
        this.command = builder.command == null ? "" : builder.command;
        this.action = builder.action == null ? "unknown" : builder.action;
        this.durationMinutes = builder.durationMinutes;
        this.totalPoints = builder.totalPoints;
        this.tierApplied = builder.tierApplied;
        this.tierDescription = builder.tierDescription == null ? "" : builder.tierDescription;
        this.offenseType = builder.offenseType == null ? "" : builder.offenseType;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The recorded infraction's id, for referring back to it. */
    public String infractionId() {
        return infractionId;
    }

    /** The console command to dispatch, placeholders already resolved. */
    public String command() {
        return command;
    }

    /** The tier's action, e.g. {@code warn}, {@code tempban}, {@code permban}. */
    public String action() {
        return action;
    }

    /**
     * The punishment length in minutes, or {@code null} when the tier has none.
     *
     * <p>Nullable rather than 0: the bot sends an explicit {@code null} for an unbounded action, and
     * a tier really can carry {@code duration: 0}, which it treats as no duration at all.
     */
    public Integer durationMinutes() {
        return durationMinutes;
    }

    /** The player's running point total after this offense. */
    public int totalPoints() {
        return totalPoints;
    }

    /** Which tier fired, 1-based. */
    public int tierApplied() {
        return tierApplied;
    }

    /** The bot's rendering of the tier, e.g. {@code tempban (1d)}. */
    public String tierDescription() {
        return tierDescription;
    }

    /** The offense type's display name. */
    public String offenseType() {
        return offenseType;
    }

    @Override
    public String toString() {
        return "OffenseResult{action='" + action + "', tier=" + tierApplied
                + ", points=" + totalPoints + ", command='" + command + "'}";
    }

    /** Mutable writer used by the response parser. */
    public static final class Builder {

        private String infractionId;
        private String command;
        private String action;
        private Integer durationMinutes;
        private int totalPoints;
        private int tierApplied;
        private String tierDescription;
        private String offenseType;

        private Builder() {
        }

        public Builder infractionId(String value) {
            this.infractionId = value;
            return this;
        }

        public Builder command(String value) {
            this.command = value;
            return this;
        }

        public Builder action(String value) {
            this.action = value;
            return this;
        }

        public Builder durationMinutes(Integer value) {
            this.durationMinutes = value;
            return this;
        }

        public Builder totalPoints(int value) {
            this.totalPoints = value;
            return this;
        }

        public Builder tierApplied(int value) {
            this.tierApplied = value;
            return this;
        }

        public Builder tierDescription(String value) {
            this.tierDescription = value;
            return this;
        }

        public Builder offenseType(String value) {
            this.offenseType = value;
            return this;
        }

        public OffenseResult build() {
            return new OffenseResult(this);
        }
    }
}
