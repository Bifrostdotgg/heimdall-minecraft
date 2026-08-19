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
    public boolean silent;

    public boolean expired(long nowMillis) {
        if (expiresAt == null || expiresAt.isEmpty()) return false;
        try {
            return java.time.Instant.parse(expiresAt).toEpochMilli() <= nowMillis;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
