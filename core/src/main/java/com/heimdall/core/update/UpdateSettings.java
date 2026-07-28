package com.heimdall.core.update;

import com.heimdall.core.json.Payload;
import java.util.concurrent.TimeUnit;

/**
 * The updater's dashboard settings, with v2's {@code config.yml} defaults behind every one.
 *
 * <h2>A view, not a snapshot</h2>
 *
 * <p>Constructed around whatever the remote config currently answers, and constructed <em>at the
 * point of use</em> rather than held in a field — the same rule as {@code WhitelistSettings}, for
 * the same reason. A settings change does not restart the updater, so a value read once when the
 * periodic check was started is permanently stale after the first dashboard edit. That is why
 * {@link UpdateService#startPeriodicChecks} takes a {@code Supplier} and re-reads it on every tick,
 * and why {@link UpdateService#joinNotice} takes one of these as an argument instead of holding it.
 *
 * <p>Reading a {@link Payload} key is a map lookup, so constructing one of these per tick and per
 * join costs nothing worth optimising.
 *
 * <p><strong>Immutable and thread-safe.</strong> Wraps an immutable {@link Payload} and owns
 * nothing else.
 */
public final class UpdateSettings {

    /** v2: {@code updates.checkIntervalHours: 12}. */
    public static final long DEFAULT_CHECK_INTERVAL_HOURS = 12L;

    private final Payload settings;

    public UpdateSettings(Payload settings) {
        this.settings = settings == null ? Payload.empty() : settings;
    }

    /** Settings with nothing set, i.e. every default. */
    public static UpdateSettings defaults() {
        return new UpdateSettings(Payload.empty());
    }

    /**
     * Whether the periodic check runs at all. v2: {@code updates.checkEnabled}, default true.
     *
     * <p>Read per tick, so switching it off in the dashboard stops the next check rather than
     * needing a restart — and switching it back on resumes without one.
     */
    public boolean checkEnabled() {
        return settings.bool("checkEnabled", true);
    }

    /**
     * Whether an admin joining the server is told about a pending update. Default true.
     *
     * <p>Separate from {@link #checkEnabled()} on purpose: a fleet operator who patches on their own
     * schedule wants the check (so {@code /hd update} works on demand) without the join spam.
     */
    public boolean notifyAdmins() {
        return settings.bool("notifyAdmins", true);
    }

    /**
     * How often to check, in hours, with a floor of 1.
     *
     * <p>The floor is v2's {@code Math.max(1, …)} and it is not cosmetic. A dashboard-supplied
     * {@code 0} would schedule a fixed-rate task with a zero period, which is a hot loop issuing
     * {@code GET plugin/latest} against the bot as fast as the scheduler can dispatch it — from
     * every server in the fleet at once, since they all read the same remote config.
     */
    public long checkIntervalHours() {
        return Math.max(1L, settings.longValue("checkIntervalHours", DEFAULT_CHECK_INTERVAL_HOURS));
    }

    /** {@link #checkIntervalHours()} in milliseconds, for the scheduler. */
    public long checkIntervalMs() {
        return TimeUnit.HOURS.toMillis(checkIntervalHours());
    }

    @Override
    public String toString() {
        return "UpdateSettings{checkEnabled=" + checkEnabled()
                + ", notifyAdmins=" + notifyAdmins()
                + ", checkIntervalHours=" + checkIntervalHours() + "}";
    }
}
