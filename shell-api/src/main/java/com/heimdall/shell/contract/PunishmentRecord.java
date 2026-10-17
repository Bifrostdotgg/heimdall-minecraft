package com.heimdall.shell.contract;

import java.util.UUID;

/**
 * One active punishment, handed from a core's {@link PunishmentBackend} to the shell.
 *
 * <p>Immutable, and made of JDK types only, so it means the same thing on both sides of the
 * classloader boundary. The shell wraps it in the public {@code HeimdallPunishment}; see that
 * interface for what each field means.
 */
public final class PunishmentRecord {

    private final String id;
    private final String type;
    private final UUID player;
    private final String playerName;
    private final String reason;
    private final long issuedAtMillis;
    private final long expiresAtMillis;
    private final String issuerName;
    private final UUID issuerUuid;
    private final boolean hidden;
    private final boolean silent;

    private PunishmentRecord(Builder builder) {
        this.id = builder.id == null ? "" : builder.id;
        this.type = builder.type == null ? "" : builder.type;
        this.player = builder.player;
        this.playerName = builder.playerName;
        this.reason = builder.reason == null ? "" : builder.reason;
        this.issuedAtMillis = Math.max(0L, builder.issuedAtMillis);
        this.expiresAtMillis = Math.max(0L, builder.expiresAtMillis);
        this.issuerName = builder.issuerName;
        this.issuerUuid = builder.issuerUuid;
        this.hidden = builder.hidden;
        this.silent = builder.silent;
    }

    public static Builder builder(String type, UUID player) {
        return new Builder(type, player);
    }

    /** Never {@code null}; empty when unknown. */
    public String id() {
        return id;
    }

    /** {@code ban}, {@code mute} or {@code freeze}. */
    public String type() {
        return type;
    }

    public UUID player() {
        return player;
    }

    /** Or {@code null}. */
    public String playerName() {
        return playerName;
    }

    /** Never {@code null}. */
    public String reason() {
        return reason;
    }

    /** Epoch milliseconds, or {@code 0} when unknown. */
    public long issuedAtMillis() {
        return issuedAtMillis;
    }

    /** Epoch milliseconds, or {@code 0} for a permanent punishment. */
    public long expiresAtMillis() {
        return expiresAtMillis;
    }

    /** Or {@code null}. */
    public String issuerName() {
        return issuerName;
    }

    /** Or {@code null}. */
    public UUID issuerUuid() {
        return issuerUuid;
    }

    public boolean hidden() {
        return hidden;
    }

    public boolean silent() {
        return silent;
    }

    @Override
    public String toString() {
        return type + ":" + player + (id.isEmpty() ? "" : " (" + id + ")");
    }

    /** Builds a {@link PunishmentRecord}. Not thread-safe; build on one thread. */
    public static final class Builder {

        private String id;
        private final String type;
        private final UUID player;
        private String playerName;
        private String reason;
        private long issuedAtMillis;
        private long expiresAtMillis;
        private String issuerName;
        private UUID issuerUuid;
        private boolean hidden;
        private boolean silent;

        private Builder(String type, UUID player) {
            this.type = type;
            this.player = player;
        }

        public Builder id(String value) {
            id = value;
            return this;
        }

        public Builder playerName(String value) {
            playerName = value;
            return this;
        }

        public Builder reason(String value) {
            reason = value;
            return this;
        }

        public Builder issuedAtMillis(long value) {
            issuedAtMillis = value;
            return this;
        }

        /** {@code 0} for a permanent punishment. */
        public Builder expiresAtMillis(long value) {
            expiresAtMillis = value;
            return this;
        }

        public Builder issuerName(String value) {
            issuerName = value;
            return this;
        }

        public Builder issuerUuid(UUID value) {
            issuerUuid = value;
            return this;
        }

        public Builder hidden(boolean value) {
            hidden = value;
            return this;
        }

        public Builder silent(boolean value) {
            silent = value;
            return this;
        }

        public PunishmentRecord build() {
            return new PunishmentRecord(this);
        }
    }
}
