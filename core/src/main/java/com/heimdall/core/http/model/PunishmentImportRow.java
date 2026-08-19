package com.heimdall.core.http.model;

/**
 * One row posted to {@code POST /punishments/import}.
 *
 * <p>Mapped from LiteBans {@code {bans}}/{@code {mutes}}/{@code {warnings}}/{@code {kicks}}
 * via {@code litebans.api.Database#prepareStatement}. Column names match
 * {@code litebans.api.Entry} (id, uuid, ip, reason, executor, time, until, silent, ipban, active).
 */
public final class PunishmentImportRow {

    public String type;
    public String targetUuid;
    public String targetName;
    public String ip;
    public String reason;
    public Integer durationMinutes;
    public boolean silent;
    public String issuedByName;
    public long issuedAtMillis;
    public String importProviderId;
    public boolean active;
}
