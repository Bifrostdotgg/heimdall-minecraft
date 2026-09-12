package com.heimdall.module.punishments;

/**
 * One active punishment in the local mirror. Gson-serialisable.
 */
public final class ActivePunishment {

    public String id;
    public String type;
    public String targetUuid;
    public String targetName;
    public String ipDigest;
    public String reason;
    public String expiresAt;
    /**
     * How long it was set for, in seconds, when the bot said so.
     *
     * <p>Additive, and {@code null} from an older bot or an older mirror file. The length is
     * otherwise derived from {@code issuedAt} to {@code expiresAt}, which is the same number
     * unless the row was edited - so this is preferred when present and the derivation stays as
     * the fallback rather than being replaced by it.
     */
    public Long durationSeconds;
    public String issuedAt;
    public String issuedByName;
    public String issuedByUuid;
    public String country;
    public String cidr;
    public boolean silent;

    public boolean expired(long nowMillis) {
        if (expiresAt == null || expiresAt.isEmpty()) return false;
        try {
            return java.time.Instant.parse(expiresAt).toEpochMilli() <= nowMillis;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** When it ends, or {@code null} for a permanent punishment or an unparseable instant. */
    Long expiresAtMillis() {
        if (expiresAt == null || expiresAt.isEmpty()) return null;
        try {
            return Long.valueOf(java.time.Instant.parse(expiresAt).toEpochMilli());
        } catch (RuntimeException e) {
            return null;
        }
    }

    long issuedAtMillis() {
        if (issuedAt == null || issuedAt.isEmpty()) return 0L;
        try {
            return java.time.Instant.parse(issuedAt).toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
