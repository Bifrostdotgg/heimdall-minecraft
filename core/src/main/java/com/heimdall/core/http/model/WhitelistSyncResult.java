package com.heimdall.core.http.model;

import com.heimdall.core.util.Lists;
import java.util.List;
import java.util.Objects;

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

    private WhitelistSyncResult(boolean notModified, Builder builder) {
        this.notModified = notModified;
        this.etag = builder.etag;
        this.hash = builder.hash;
        this.count = builder.count;
        this.generatedAt = builder.generatedAt;
        this.players = Lists.copyOf(builder.players);
    }

    /** Nothing changed since the ETag the caller sent. {@link #players()} is empty and meaningless. */
    public static WhitelistSyncResult notModified(String etag) {
        return new WhitelistSyncResult(true, new Builder().etag(etag));
    }

    /**
     * A full whitelist dump.
     *
     * <p>A builder rather than five positional arguments, three of which are Strings — {@code etag},
     * {@code hash} and {@code generatedAt} — that the compiler cannot tell apart.
     */
    public static Builder modified() {
        return new Builder();
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
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WhitelistSyncResult)) {
            return false;
        }
        WhitelistSyncResult that = (WhitelistSyncResult) other;
        return notModified == that.notModified
                && count == that.count
                && Objects.equals(etag, that.etag)
                && Objects.equals(hash, that.hash)
                && Objects.equals(generatedAt, that.generatedAt)
                && players.equals(that.players);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Boolean.valueOf(notModified), etag, hash, Integer.valueOf(count),
                generatedAt, players);
    }

    @Override
    public String toString() {
        return notModified
                ? "WhitelistSyncResult{notModified, etag=" + etag + "}"
                : "WhitelistSyncResult{" + players.size() + " players, etag=" + etag + "}";
    }

    /** Mutable writer for the modified shape. */
    public static final class Builder {

        private String etag;
        private String hash;
        private int count;
        private String generatedAt;
        private List<WhitelistSyncEntry> players;

        private Builder() {
        }

        /** The {@code ETag} header verbatim, quotes and all. */
        public Builder etag(String value) {
            this.etag = value;
            return this;
        }

        /** The bot's own hash of the whitelist, out of the body. */
        public Builder hash(String value) {
            this.hash = value;
            return this;
        }

        public Builder count(int value) {
            this.count = value;
            return this;
        }

        /** ISO-8601, from the body. */
        public Builder generatedAt(String value) {
            this.generatedAt = value;
            return this;
        }

        public Builder players(List<WhitelistSyncEntry> value) {
            this.players = value;
            return this;
        }

        public WhitelistSyncResult build() {
            return new WhitelistSyncResult(false, this);
        }
    }
}
