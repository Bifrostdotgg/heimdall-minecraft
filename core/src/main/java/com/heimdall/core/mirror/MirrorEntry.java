package com.heimdall.core.mirror;

/**
 * One mirrored value and the three timestamps that decide how long it may be trusted.
 *
 * <p><strong>Immutable.</strong> Every change produces a new instance, and {@link MirrorStore}
 * publishes it through {@code ConcurrentHashMap.compute}, so a reader sees either the whole old
 * entry or the whole new one. That is load-bearing on two counts:
 *
 * <ul>
 *   <li>Mutating fields in place makes read-modify-write on the expiry a race between the login
 *       thread and the sync scheduler, so a reconcile and a join extension can interleave into an
 *       expiry neither of them intended.
 *   <li>The oldest supported servers run 32-bit JVMs, where a non-volatile {@code long} read is
 *       permitted to tear into two halves. A torn {@code cacheExpiry} is a whitelist decision made
 *       against a timestamp that never existed.
 * </ul>
 *
 * <p>It also makes {@code MirrorStore}'s snapshot a real snapshot: copying the map is enough,
 * because the values cannot change underneath the serializer while it writes them out.
 *
 * <p>Field names are the on-disk shape, read reflectively by Gson. Renaming one silently loses that
 * field's value for every already-deployed server on its next boot.
 *
 * <p>The distinction between {@link #cacheExpiry()} and {@link #lastVerified()} is the whole design:
 *
 * <ul>
 *   <li>{@code cacheExpiry} moves on ordinary activity (a join, a leave).
 *   <li>{@code lastVerified} moves <strong>only</strong> when the bot confirmed this value.
 * </ul>
 *
 * <p>Without the second, a player removed from the whitelist keeps access forever by rejoining once
 * per extension window. See {@link MirrorStore} for how the ceiling is enforced.
 *
 * @param <T> the mirrored value type
 */
final class MirrorEntry<T> {

    private final T value;
    private final long lastConnection;
    private final long cacheExpiry;

    /**
     * When the bot last confirmed this value, epoch millis.
     *
     * <p>Entries persisted before this field existed deserialize to 0, whose ceiling is far in the
     * past — so they force a re-verification rather than being served on a stale expiry. That is
     * the intended migration behaviour, not an accident of the default.
     */
    private final long lastVerified;

    /**
     * For Gson, which sets the final fields reflectively after constructing the instance.
     *
     * <p>Reflective assignment to a non-static final field is legal once {@code setAccessible(true)}
     * has been called, which is how Gson has always handled final fields.
     */
    private MirrorEntry() {
        this(null, 0L, 0L, 0L);
    }

    MirrorEntry(T value, long lastConnection, long cacheExpiry, long lastVerified) {
        this.value = value;
        this.lastConnection = lastConnection;
        this.cacheExpiry = cacheExpiry;
        this.lastVerified = lastVerified;
    }

    T value() {
        return value;
    }

    long lastConnection() {
        return lastConnection;
    }

    long cacheExpiry() {
        return cacheExpiry;
    }

    long lastVerified() {
        return lastVerified;
    }

    /**
     * A copy recording ordinary activity: a new connection time and expiry, the same
     * {@code lastVerified}. Activity is not evidence, so the ceiling does not move.
     */
    MirrorEntry<T> withActivity(long lastConnection, long cacheExpiry) {
        return new MirrorEntry<T>(value, lastConnection, cacheExpiry, lastVerified);
    }

    /** A copy recording a fresh confirmation from the bot: {@code lastVerified} moves to {@code now}. */
    MirrorEntry<T> verified(T replacement, long now, long cacheExpiry) {
        return new MirrorEntry<T>(replacement, now, cacheExpiry, now);
    }

    /**
     * A copy carrying a new value and nothing else — no timestamp moves.
     *
     * <p>For refreshing a detail nobody had to ask the bot about, such as a player's current
     * username. Advancing {@code lastVerified} here would hand out a fresh ceiling for information
     * that was never verified.
     */
    MirrorEntry<T> withValue(T replacement) {
        return new MirrorEntry<T>(replacement, lastConnection, cacheExpiry, lastVerified);
    }

    @Override
    public String toString() {
        return "MirrorEntry{expiry=" + cacheExpiry + ", verified=" + lastVerified + "}";
    }
}
