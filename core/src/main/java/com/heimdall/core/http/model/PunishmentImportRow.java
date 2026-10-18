package com.heimdall.core.http.model;

import com.heimdall.core.punish.PunishmentIp;
import java.util.List;

/**
 * One row posted to {@code POST /punishments/import}.
 *
 * <p>Mapped from LiteBans {@code {bans}}/{@code {mutes}}/{@code {warnings}}/{@code {kicks}}
 * via {@code litebans.api.Database#prepareStatement}. Column names match
 * {@code litebans.api.Entry} (id, uuid, ip, reason, executor, time, until, silent, ipban, active).
 *
 * <p>{@link #ip} is a local scratch field from the SQL row. {@link #hashIpWith} turns it into
 * {@link #ipDigest} and clears it before the HTTP body is built. The wire never carries a raw
 * address.
 */
public final class PunishmentImportRow {

    public String type;
    public String targetUuid;
    public String targetName;
    /** SQL scratch. Cleared by {@link #hashIpWith}; never serialised. */
    public String ip;
    public String ipDigest;
    public String reason;
    /**
     * The length in seconds. Wins over {@link #durationMinutes} on the bot when both arrive, and is
     * what the LiteBans importer sets; the minutes field is still sent for a bot older than that.
     */
    public Long durationSeconds;
    public Integer durationMinutes;
    public boolean silent;
    public String issuedByName;
    /** The staff member's dashed UUID, or {@code null} for the console or an unknown issuer. */
    public String issuedByUuid;
    public long issuedAtMillis;
    public String importProviderId;
    /**
     * Set instead of {@link #importProviderId} when the only stable id is the address itself.
     * {@link #hashIpWith} appends the salted digest, so the id never carries the raw address.
     */
    public String importProviderIdIpPrefix;
    public boolean active;
    /** Who lifted it, for a row that arrives already lifted by a person. */
    public String revokedBy;
    public String revokeReason;
    public long revokedAtMillis;
    /**
     * The provider's server scope when it is narrower than the whole network. Never sent: the bot
     * has no per-server punishments, so a scoped row is imported network-wide and only counted.
     */
    public String serverScope;

    /**
     * HMAC the scratch IP with the guild salt, then drop the raw address.
     *
     * <p>An empty salt must not produce a digest: HMAC with {@code ""} is well-defined and would
     * look like a real hash while matching nothing the live plugin computes.
     */
    public void hashIpWith(String salt) {
        if (ip != null && !ip.isEmpty() && salt != null && !salt.isEmpty()) {
            ipDigest = PunishmentIp.hash(ip, salt);
        }
        ip = null;
        if (importProviderId == null && importProviderIdIpPrefix != null && ipDigest != null) {
            importProviderId = importProviderIdIpPrefix + ipDigest;
        }
        importProviderIdIpPrefix = null;
    }

    /** {@link #hashIpWith} every row. Null list is a no-op. */
    public static void hashIps(List<PunishmentImportRow> rows, String salt) {
        if (rows == null) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            PunishmentImportRow row = rows.get(i);
            if (row != null) {
                row.hashIpWith(salt);
            }
        }
    }
}
