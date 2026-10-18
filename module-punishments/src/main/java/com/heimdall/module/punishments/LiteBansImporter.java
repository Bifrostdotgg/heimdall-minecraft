package com.heimdall.module.punishments;

import com.heimdall.core.http.model.PunishmentImportRow;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.punish.PunishmentParser;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads LiteBans tables through {@code litebans.api.Database#prepareStatement}.
 *
 * <p>Schema is taken from LiteBans 2.x: the {@code {bans}}/{@code {mutes}}/{@code {warnings}}/
 * {@code {kicks}} placeholders {@code Database#prepareStatement} substitutes with
 * {@code table_prefix} (default {@code litebans_}). SQL strings inside the jar are encrypted; the
 * public API is the stable contract.
 *
 * <pre>
 *   id, uuid, ip, reason,
 *   banned_by_uuid, banned_by_name,
 *   removed_by_uuid, removed_by_name, removed_by_reason, removed_by_date,
 *   time (start millis), until (end millis, -1/0 = permanent),
 *   server_scope, silent, ipban, active
 * </pre>
 *
 * <p>{@code SELECT *} and a by-name read of each column, rather than a column list: a table that
 * lacks one of them (older LiteBans versions differ on {@code {kicks}}) then costs that field, where
 * a named list failed the whole table.
 *
 * <p>Raw IPs stay in the scratch {@code ip} field on the row until
 * {@link com.heimdall.core.http.model.PunishmentImportRow#hashIpWith} runs,
 * which is before the HTTP body is built. The bot never sees an address.
 */
final class LiteBansImporter {

    private static final Pattern UUID_DASHED = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern UUID_PLAIN = Pattern.compile("^[0-9a-fA-F]{32}$");

    /** One row of {@code {history}}, LiteBans' log of which address each player joined from. */
    static final class Sighting {
        final String uuid;
        final String name;
        final String ip;
        final long seenAt;

        Sighting(String uuid, String name, String ip, long seenAt) {
            this.uuid = uuid;
            this.name = name;
            this.ip = ip;
            this.seenAt = seenAt;
        }
    }

    /** Receives each history row as it is read, so a large table is never held in memory. */
    interface SightingSink {
        void accept(Sighting sighting);
    }

    /** What one LiteBans pass produced. */
    static final class Result {
        final List<PunishmentImportRow> rows = new ArrayList<PunishmentImportRow>();
        /** Rows read per table, by placeholder. */
        final Map<String, Integer> byTable = new HashMap<String, Integer>();
        /** Rows read but not importable (a ban or mute with no player left). */
        int unusable;
        boolean installed;
        int historyRows;
        boolean historyRead;
    }

    private LiteBansImporter() {
    }

    static Result readAll(HeimdallLogger logger, SightingSink sightings) {
        Result result = new Result();
        Object database;
        try {
            Class<?> cls = Class.forName("litebans.api.Database");
            database = cls.getMethod("get").invoke(null);
        } catch (ClassNotFoundException e) {
            logger.debug(() -> "LiteBans is not installed; LiteBans import skipped");
            return result;
        } catch (Throwable e) {
            logger.error("LiteBans Database.get() failed", e);
            return result;
        }
        result.installed = true;
        // History first: the names it carries go onto the punishment rows.
        Map<String, String> names = readHistory(logger, database, sightings, result);
        readTable(logger, database, "{bans}", "ban", result);
        readTable(logger, database, "{mutes}", "mute", result);
        readTable(logger, database, "{warnings}", "warn", result);
        readTable(logger, database, "{kicks}", "kick", result);
        for (PunishmentImportRow row : result.rows) {
            if (row.targetName == null && row.targetUuid != null) {
                row.targetName = names.get(row.targetUuid.toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    /**
     * Streams {@code {history}} into {@code sightings} and returns the latest name per UUID.
     *
     * <p>{@code {history}} is not among the placeholders the API stub documents. If LiteBans does
     * not substitute it, the statement fails, one line says so, and the punishment import goes on
     * without names: they are a convenience the bot can also fill on read.
     */
    private static Map<String, String> readHistory(
            HeimdallLogger logger, Object database, SightingSink sightings, Result out) {
        final Map<String, String> names = new HashMap<String, String>();
        final Map<String, Long> namedAt = new HashMap<String, Long>();
        boolean ok = query(logger, database, "{history}", new RowHandler() {
            @Override
            public void row(Map<String, Object> row) {
                Sighting sighting = mapSighting(row);
                if (sighting == null) return;
                out.historyRows++;
                if (sighting.name != null) {
                    String key = sighting.uuid.toLowerCase(Locale.ROOT);
                    Long previous = namedAt.get(key);
                    if (previous == null || sighting.seenAt >= previous.longValue()) {
                        names.put(key, sighting.name);
                        namedAt.put(key, Long.valueOf(sighting.seenAt));
                    }
                }
                if (sightings != null && sighting.ip != null) {
                    sightings.accept(sighting);
                }
            }
        });
        out.historyRead = ok;
        if (ok) {
            logger.info("LiteBans {history} -> " + out.historyRows + " rows, " + names.size()
                    + " player names");
        }
        return names;
    }

    private static void readTable(
            HeimdallLogger logger, Object database, String placeholder, final String type, final Result out) {
        final int[] read = new int[1];
        boolean ok = query(logger, database, placeholder, new RowHandler() {
            @Override
            public void row(Map<String, Object> row) {
                read[0]++;
                PunishmentImportRow mapped = mapRow(row, type);
                if (mapped == null) {
                    out.unusable++;
                } else {
                    out.rows.add(mapped);
                }
            }
        });
        if (ok) {
            out.byTable.put(placeholder, Integer.valueOf(read[0]));
            logger.info("LiteBans " + placeholder + " -> " + read[0] + " rows");
        }
    }

    interface RowHandler {
        void row(Map<String, Object> row);
    }

    /** Runs {@code SELECT * FROM placeholder}, handing each row over as lower-cased column names. */
    private static boolean query(
            HeimdallLogger logger, Object database, String placeholder, RowHandler handler) {
        PreparedStatement statement = null;
        ResultSet result = null;
        try {
            Method prepare = database.getClass().getMethod("prepareStatement", String.class);
            Object prepared = prepare.invoke(database, "SELECT * FROM " + placeholder);
            if (!(prepared instanceof PreparedStatement)) {
                logger.warn("LiteBans prepareStatement did not return a PreparedStatement for " + placeholder);
                return false;
            }
            statement = (PreparedStatement) prepared;
            result = statement.executeQuery();
            ResultSetMetaData meta = result.getMetaData();
            int columns = meta.getColumnCount();
            String[] labels = new String[columns];
            for (int i = 0; i < columns; i++) {
                labels[i] = meta.getColumnLabel(i + 1).toLowerCase(Locale.ROOT);
            }
            while (result.next()) {
                Map<String, Object> row = new HashMap<String, Object>();
                for (int i = 0; i < columns; i++) {
                    row.put(labels[i], result.getObject(i + 1));
                }
                handler.row(row);
            }
            return true;
        } catch (Throwable e) {
            logger.error("LiteBans import could not read " + placeholder, e);
            return false;
        } finally {
            closeQuietly(result);
            closeQuietly(statement);
        }
    }

    /**
     * One punishment row, or {@code null} when it names nobody Heimdall can hold it against. Pure,
     * so the mapping is tested without a database.
     */
    static PunishmentImportRow mapRow(Map<String, Object> row, String type) {
        PunishmentImportRow out = new PunishmentImportRow();
        out.type = type;
        long id = longOf(row.get("id"), 0L);
        if (id <= 0L) return null;
        boolean ipban = "ban".equals(type) && boolOf(row.get("ipban"), false);
        if (ipban) out.type = "ipban";
        out.importProviderId = providerId(out.type, id);
        out.targetUuid = uuidOf(row.get("uuid"));
        out.ip = stringOf(row.get("ip"));
        if (out.ip != null && (out.ip.startsWith("#") || !out.ip.matches("^[0-9a-fA-F:.]+$"))) {
            // `#offline#`, `#undefined#` and the like: placeholders, not addresses.
            out.ip = null;
        }
        if (out.targetUuid == null) {
            // An IP ban can stand on its address alone. Anything else names a player or nothing.
            if (!"ipban".equals(out.type) || out.ip == null) return null;
        }
        out.reason = stringOf(row.get("reason"));
        out.issuedByName = stringOf(row.get("banned_by_name"));
        out.issuedByUuid = uuidOf(row.get("banned_by_uuid"));
        long start = longOf(row.get("time"), 0L);
        out.issuedAtMillis = start > 0L ? start : 0L;
        long until = longOf(row.get("until"), 0L);
        if (until > 0L && start > 0L && until > start
                && !"kick".equals(type) && !"warn".equals(type)) {
            long seconds = Math.max(1L, (until - start) / 1000L);
            // Past the bot's ceiling a length is how LiteBans users spell "forever" (`/ban x 100y`),
            // and the bot would refuse the row outright. The hook mirrors those as permanent; so
            // does the import, so the two never disagree about the same punishment.
            if (seconds <= PunishmentParser.MAX_ISSUE_SECONDS) {
                out.durationSeconds = Long.valueOf(seconds);
                out.durationMinutes = Integer.valueOf((int) ((seconds + 59L) / 60L));
            }
        }
        out.silent = boolOf(row.get("silent"), false);
        out.active = boolOf(row.get("active"), !"kick".equals(type));
        if (!out.active) {
            String removedBy = stringOf(row.get("removed_by_name"));
            // LiteBans writes `#expired` (and other `#` markers) when it retires a row itself. That
            // is not a person lifting it, so it stays an expiry the bot works out from the length.
            if (removedBy != null && !removedBy.startsWith("#")) {
                out.revokedBy = removedBy;
                out.revokeReason = stringOf(row.get("removed_by_reason"));
                out.revokedAtMillis = millisOf(row.get("removed_by_date"));
            }
        }
        String scope = stringOf(row.get("server_scope"));
        if (scope != null && !"*".equals(scope) && !"global".equalsIgnoreCase(scope)) {
            out.serverScope = scope;
        }
        return out;
    }

    /** The import id the hook bridge also sends, so a hooked row and its imported twin match. */
    static String providerId(String type, long id) {
        return type + ":" + id;
    }

    static Sighting mapSighting(Map<String, Object> row) {
        String uuid = uuidOf(row.get("uuid"));
        if (uuid == null) return null;
        String ip = stringOf(row.get("ip"));
        if (ip != null && (ip.startsWith("#") || !ip.matches("^[0-9a-fA-F:.]+$"))) ip = null;
        long seenAt = millisOf(row.get("date"));
        return new Sighting(uuid, stringOf(row.get("name")), ip, seenAt);
    }

    /**
     * A dashed UUID, or {@code null} for {@code CONSOLE}, {@code #offline#} and the rest. Case is
     * kept as LiteBans stored it, which is also what the hook bridge sends.
     */
    static String uuidOf(Object value) {
        String raw = stringOf(value);
        if (raw == null) return null;
        if (UUID_PLAIN.matcher(raw).matches()) {
            raw = raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12, 16)
                    + "-" + raw.substring(16, 20) + "-" + raw.substring(20);
        }
        if (!UUID_DASHED.matcher(raw).matches()) return null;
        // The nil UUID is what some LiteBans versions write for the console.
        if ("00000000-0000-0000-0000-000000000000".equals(raw)) return null;
        return raw;
    }

    static String stringOf(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    static long longOf(Object value, long fallback) {
        if (value instanceof Number) return ((Number) value).longValue();
        String s = stringOf(value);
        if (s == null) return fallback;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** BIT(1) comes back as a Boolean from MySQL and as a number from SQLite and H2. */
    static boolean boolOf(Object value, boolean fallback) {
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            return bytes.length > 0 && bytes[0] != 0;
        }
        String s = stringOf(value);
        if (s == null) return fallback;
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    /** A TIMESTAMP or DATETIME column as epoch millis, or 0 when there is none. */
    static long millisOf(Object value) {
        if (value instanceof java.util.Date) return ((java.util.Date) value).getTime();
        if (value instanceof java.time.LocalDateTime) {
            return ((java.time.LocalDateTime) value)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (value instanceof java.time.OffsetDateTime) {
            return ((java.time.OffsetDateTime) value).toInstant().toEpochMilli();
        }
        if (value instanceof Number) return ((Number) value).longValue();
        String s = stringOf(value);
        if (s == null) return 0L;
        try {
            return java.sql.Timestamp.valueOf(s).getTime();
        } catch (IllegalArgumentException e) {
            return 0L;
        }
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
