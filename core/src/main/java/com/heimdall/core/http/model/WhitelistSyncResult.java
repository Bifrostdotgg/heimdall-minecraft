package com.heimdall.core.http.model;

import com.heimdall.core.util.Lists;
import java.util.List;

/**
 * The answer to {@code GET /whitelist/sync}, including the case where there was nothing to send.
 *
 * <p>The endpoint is polled, and the whole point of the ETag is that most polls return {@code 304
 * Not Modified} with no body at all. That is a <em>result</em>, not an error and not an empty list
 * — a caller that could not tell the three apart would reconcile the mirror against an empty set on
 * every unchanged poll and prune the entire whitelist. Hence {@link #notModified()}.
 */
public final class WhitelistSyncResult {

    private final boolean notModified;
    private final String etag;
    private final String hash;
    private final int count;
    private final String generatedAt;
    private final List<WhitelistSyncEntry> players;

    private WhitelistSyncResult(
            boolean notModified,
            String etag,
            String hash,
            int count,
            String generatedAt,
            List<WhitelistSyncEntry> players) {
        this.notModified = notModified;
        this.etag = etag;
        this.hash = hash;
        this.count = count;
        this.generatedAt = generatedAt;
        this.players = Lists.copyOf(players);
    }

    /** Nothing changed since the ETag the caller sent. {@link #players()} is empty and meaningless. */
    public static WhitelistSyncResult notModified(String etag) {
        return new WhitelistSyncResult(true, etag, null, 0, null, null);
    }

    /** A full whitelist dump. */
    public static WhitelistSyncResult modified(
            String etag, String hash, int count, String generatedAt, List<WhitelistSyncEntry> players) {
        return new WhitelistSyncResult(false, etag, hash, count, generatedAt, players);
    }

    /**
     * Whether the server answered 304.
     *
     * <p>When {@code true}, do not touch the mirror.
     */
    public boolean notModified() {
        return notModified;
    }

    /** The {@code ETag} header, quotes included as sent. Pass it back on the next poll. */
    public String etag() {
        return etag;
    }

    /** The bot's own hash of the whitelist, from the body. {@code null} when {@link #notModified()}. */
    public String hash() {
        return hash;
    }

    /** How many players the dump contained. */
    public int count() {
        return count;
    }

    /** When the bot generated the dump, ISO-8601. {@code null} when {@link #notModified()}. */
    public String generatedAt() {
        return generatedAt;
    }

    /** The whitelisted players. Empty when {@link #notModified()}. */
    public List<WhitelistSyncEntry> players() {
        return players;
    }

    @Override
    public String toString() {
        return notModified
                ? "WhitelistSyncResult{notModified, etag=" + etag + "}"
                : "WhitelistSyncResult{" + players.size() + " players, etag=" + etag + "}";
    }
}
