package com.heimdall.core.http.model;

import com.heimdall.core.util.Lists;
import java.util.List;
import java.util.Objects;

/**
 * One configured offense category, as served by {@code GET /offense-types}.
 *
 * <p>The escalation tiers are deliberately not modelled: the bot resolves the tier when an offense
 * is reported and sends back the command to run. Mirroring the tier table here would put the
 * escalation maths in two places, and the plugin's copy would be the one that goes stale.
 */
public final class OffenseType {

    private final String typeId;
    private final String displayName;
    private final String description;
    private final List<String> offenses;
    private final boolean enabled;

    private OffenseType(Builder builder) {
        this.typeId = builder.typeId;
        this.displayName = builder.displayName;
        this.description = builder.description == null ? "" : builder.description;
        this.offenses = Lists.copyOf(builder.offenses);
        this.enabled = builder.enabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Stable identifier, e.g. {@code cheating}. */
    public String typeId() {
        return typeId;
    }

    /** Human-readable name, e.g. {@code Cheating}. */
    public String displayName() {
        return displayName;
    }

    /** Longer description; empty rather than {@code null} when the bot sent none. */
    public String description() {
        return description;
    }

    /** The slugs that fall under this type — the values tab-completion offers. */
    public List<String> offenses() {
        return offenses;
    }

    /** Whether staff may report against this type. */
    public boolean enabled() {
        return enabled;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OffenseType)) {
            return false;
        }
        OffenseType that = (OffenseType) other;
        return enabled == that.enabled
                && Objects.equals(typeId, that.typeId)
                && Objects.equals(displayName, that.displayName)
                && description.equals(that.description)
                && offenses.equals(that.offenses);
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeId, displayName, description, offenses, Boolean.valueOf(enabled));
    }

    @Override
    public String toString() {
        return displayName + " (" + typeId + ") — offenses: " + String.join(", ", offenses);
    }

    /** Mutable writer. */
    public static final class Builder {

        private String typeId;
        private String displayName;
        private String description;
        private List<String> offenses;
        private boolean enabled;

        private Builder() {
        }

        public Builder typeId(String value) {
            this.typeId = value;
            return this;
        }

        public Builder displayName(String value) {
            this.displayName = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder offenses(List<String> value) {
            this.offenses = value;
            return this;
        }

        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        public OffenseType build() {
            return new OffenseType(this);
        }
    }
}
