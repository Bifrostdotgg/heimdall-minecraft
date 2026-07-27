package com.heimdall.core.mirror;

/**
 * One mirrored value and the three timestamps that decide how long it may be trusted.
 *
 * <p>Fields are mutable and read reflectively by Gson — this is the on-disk shape. A field renamed
 * here silently loses that field's value for every already-deployed server on its next boot.
 *
 * <p>The distinction between {@link #cacheExpiry()} and {@link #lastVerified()} is the whole design:
 *
 * <ul>
 *   <li>{@code cacheExpiry} slides forward on ordinary activity (a join, a leave).
 *   <li>{@code lastVerified} moves <strong>only</strong> when the bot confirmed this value.
 * </ul>
 *
 * <p>Without the second, a player removed from the whitelist keeps access forever by rejoining once
 * per extension window. See {@link MirrorStore} for how the ceiling is enforced.
 *
 * @param <T> the mirrored value type
 */
final class MirrorEntry<T> {

    private T value;
    private long lastConnection;
    private long cacheExpiry;

    /**
     * When the bot last confirmed this value, epoch millis.
     *
     * <p>Entries persisted before this field existed deserialize to 0, whose ceiling is far in the
     * past — so they force a re-verification rather than being served on a stale expiry. That is
     * the intended migration behaviour, not an accident of the default.
     */
    private long lastVerified;

    /** Gson needs this. */
    MirrorEntry() {
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

    void value(T replacement) {
        this.value = replacement;
    }

    long lastConnection() {
        return lastConnection;
    }

    void lastConnection(long millis) {
        this.lastConnection = millis;
    }

    long cacheExpiry() {
        return cacheExpiry;
    }

    void cacheExpiry(long millis) {
        this.cacheExpiry = millis;
    }

    long lastVerified() {
        return lastVerified;
    }

    void lastVerified(long millis) {
        this.lastVerified = millis;
    }

    @Override
    public String toString() {
        return "MirrorEntry{expiry=" + cacheExpiry + ", verified=" + lastVerified + "}";
    }
}
