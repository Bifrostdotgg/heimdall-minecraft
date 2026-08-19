package com.heimdall.module.punishments;

import com.heimdall.core.http.model.PunishmentImportRow;
import com.heimdall.core.log.HeimdallLogger;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads LiteBans tables through {@code litebans.api.Database#prepareStatement}.
 *
 * <p>Schema is taken from LiteBans 2.18.7's public {@code litebans.api.Entry}
 * fields and the {@code {bans}}/{@code {mutes}}/{@code {warnings}}/{@code {kicks}}
 * placeholders {@code Database#prepareStatement} substitutes with
 * {@code table_prefix} (default {@code litebans_}). SQL strings inside the jar
 * are encrypted; the public API is the stable contract.
 *
 * <pre>
 *   id, uuid, ip, reason,
 *   banned_by_uuid, banned_by_name,
 *   time (start millis), until (end millis, -1/0 = permanent),
 *   silent, ipban, active
 * </pre>
 *
 * <p>IPs are sent to the bot as raw strings on this hop only so the bot can
 * HMAC them with the guild salt before they are stored. They never persist on
 * Heimdall in the clear.
 */
final class LiteBansImporter {

    private static final String SELECT =
            "SELECT id, uuid, ip, reason, banned_by_uuid, banned_by_name, time, until, silent, ipban, active FROM ";

    private LiteBansImporter() {
    }

    static List<PunishmentImportRow> readAll(HeimdallLogger logger) {
        List<PunishmentImportRow> rows = new ArrayList<PunishmentImportRow>();
        Object database;
        try {
            Class<?> cls = Class.forName("litebans.api.Database");
            database = cls.getMethod("get").invoke(null);
        } catch (ClassNotFoundException e) {
            logger.warn("LiteBans is not installed; import skipped");
            return rows;
        } catch (Throwable e) {
            logger.error("LiteBans Database.get() failed", e);
            return rows;
        }
        readTable(logger, database, "{bans}", "ban", rows);
        readTable(logger, database, "{mutes}", "mute", rows);
        readTable(logger, database, "{warnings}", "warn", rows);
        readTable(logger, database, "{kicks}", "kick", rows);
        return rows;
    }

    private static void readTable(
            HeimdallLogger logger, Object database, String placeholder, String type, List<PunishmentImportRow> out) {
        PreparedStatement statement = null;
        ResultSet result = null;
        try {
            Method prepare = database.getClass().getMethod("prepareStatement", String.class);
            Object prepared = prepare.invoke(database, SELECT + placeholder);
            if (!(prepared instanceof PreparedStatement)) {
                logger.warn("LiteBans prepareStatement did not return a PreparedStatement for " + placeholder);
                return;
            }
            statement = (PreparedStatement) prepared;
            result = statement.executeQuery();
            int n = 0;
            while (result.next()) {
                PunishmentImportRow row = mapRow(result, type);
                if (row != null) {
                    out.add(row);
                    n++;
                }
            }
            logger.info("LiteBans " + placeholder + " -> " + n + " rows");
        } catch (Throwable e) {
            logger.error("LiteBans import failed for " + placeholder, e);
        } finally {
            closeQuietly(result);
            closeQuietly(statement);
        }
    }

    private static PunishmentImportRow mapRow(ResultSet result, String type) throws java.sql.SQLException {
        PunishmentImportRow row = new PunishmentImportRow();
        row.type = type;
        long id = result.getLong("id");
        row.importProviderId = type + ":" + id;
        String uuid = result.getString("uuid");
        row.targetUuid = uuid;
        row.ip = result.getString("ip");
        row.reason = result.getString("reason");
        row.issuedByName = result.getString("banned_by_name");
        long start = result.getLong("time");
        row.issuedAtMillis = start;
        long until = 0L;
        try {
            until = result.getLong("until");
        } catch (java.sql.SQLException ignored) {
            until = 0L;
        }
        if (until > 0 && start > 0 && until > start) {
            row.durationMinutes = Integer.valueOf((int) Math.max(1L, (until - start) / 60000L));
        }
        try {
            row.silent = result.getBoolean("silent");
        } catch (java.sql.SQLException ignored) {
            row.silent = false;
        }
        boolean ipban = false;
        try {
            ipban = result.getBoolean("ipban");
        } catch (java.sql.SQLException ignored) {
            ipban = false;
        }
        if (ipban && "ban".equals(type)) {
            row.type = "ipban";
            row.importProviderId = "ipban:" + id;
        }
        try {
            row.active = result.getBoolean("active");
        } catch (java.sql.SQLException ignored) {
            row.active = !"kick".equals(type);
        }
        return row;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
            // ignore
        }
    }
}
