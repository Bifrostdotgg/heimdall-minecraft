package com.heimdall.core.http.model;

import com.heimdall.core.util.Lists;
import java.util.List;

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

    public OffenseType(
            String typeId, String displayName, String description, List<String> offenses, boolean enabled) {
        this.typeId = typeId;
        this.displayName = displayName;
        this.description = description == null ? "" : description;
        this.offenses = Lists.copyOf(offenses);
        this.enabled = enabled;
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
    public String toString() {
        return displayName + " (" + typeId + ") — offenses: " + String.join(", ", offenses);
    }
}
