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
    public Integer durationMinutes;
    public boolean silent;
    public String issuedByName;
    public long issuedAtMillis;
    public String importProviderId;
    public boolean active;

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
