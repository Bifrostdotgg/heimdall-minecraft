package com.heimdall.module.punishments;

import com.heimdall.core.http.model.PunishmentImportRow;
import com.heimdall.core.log.HeimdallLogger;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Reads BanManager current bans/mutes/warnings via BmAPI reflection.
 */
final class BanManagerImporter {

    private BanManagerImporter() {
    }

    static List<PunishmentImportRow> read(HeimdallLogger logger) {
        List<PunishmentImportRow> rows = new ArrayList<PunishmentImportRow>();
        try {
            Class<?> api = Class.forName("me.confuser.banmanager.common.api.BmAPI");
            collect(logger, rows, api, "getBans", "ban");
            collect(logger, rows, api, "getMutes", "mute");
            collect(logger, rows, api, "getWarnings", "warn");
            collect(logger, rows, api, "getIpBans", "ipban");
            logger.info("BanManager -> " + rows.size() + " rows");
        } catch (ClassNotFoundException absent) {
            tryLegacy(logger, rows);
        } catch (Throwable failed) {
            logger.warn("BanManager import failed: " + failed);
        }
        return rows;
    }

    private static void tryLegacy(HeimdallLogger logger, List<PunishmentImportRow> rows) {
        try {
            Class.forName("me.confuser.banmanager.BmAPI");
            logger.debug(() -> "BanManager BmAPI found but list methods were not invoked");
        } catch (ClassNotFoundException absent) {
            logger.debug(() -> "BanManager is not on the classpath");
        }
    }

    private static void collect(
            HeimdallLogger logger, List<PunishmentImportRow> rows, Class<?> api, String method, String type) {
        try {
            Method m = api.getMethod(method);
            Object result = m.invoke(null);
            if (!(result instanceof Collection<?>)) return;
            for (Object data : (Collection<?>) result) {
                PunishmentImportRow row = map(data, type);
                if (row != null) rows.add(row);
            }
        } catch (NoSuchMethodException missing) {
            logger.debug(() -> "BanManager has no " + method);
        } catch (Throwable failed) {
            logger.debug(() -> "BanManager " + method + " failed: " + failed);
        }
    }

    private static PunishmentImportRow map(Object data, String type) {
        if (data == null) return null;
        PunishmentImportRow row = new PunishmentImportRow();
        row.type = type;
        Object player = call(data, "getPlayer");
        if (player != null) {
            Object uuid = call(player, "getUniqueId");
            if (uuid instanceof UUID) row.targetUuid = uuid.toString();
            row.targetName = string(call(player, "getName"));
        }
        row.reason = string(call(data, "getReason"));
        Object actor = call(data, "getActor");
        if (actor != null) {
            row.issuedByName = string(call(actor, "getName"));
        }
        Object created = call(data, "getCreated");
        if (created instanceof Number) {
            long v = ((Number) created).longValue();
            row.issuedAtMillis = v < 10_000_000_000L ? v * 1000L : v;
        }
        Object expires = call(data, "getExpires");
        if (expires instanceof Number) {
            long until = ((Number) expires).longValue();
            if (until > 0) {
                long untilMs = until < 10_000_000_000L ? until * 1000L : until;
                if (row.issuedAtMillis > 0 && untilMs > row.issuedAtMillis) {
                    row.durationMinutes = Integer.valueOf(
                            (int) Math.max(1L, (untilMs - row.issuedAtMillis) / 60000L));
                }
            }
        }
        if ("ipban".equals(type)) {
            row.ip = string(call(data, "getIp"));
        }
        row.importProviderId = "banmanager:" + type + ":" + row.targetUuid;
        row.active = true;
        return row;
    }

    private static Object call(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
