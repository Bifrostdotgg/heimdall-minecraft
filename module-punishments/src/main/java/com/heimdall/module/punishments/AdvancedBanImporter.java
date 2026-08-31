package com.heimdall.module.punishments;

import com.heimdall.core.http.model.PunishmentImportRow;
import com.heimdall.core.log.HeimdallLogger;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads AdvancedBan's live punishment list by reflection. No-ops when AdvancedBan is absent.
 */
final class AdvancedBanImporter {

    private AdvancedBanImporter() {
    }

    static List<PunishmentImportRow> read(HeimdallLogger logger) {
        List<PunishmentImportRow> rows = new ArrayList<PunishmentImportRow>();
        try {
            Class<?> manager = Class.forName("me.leoko.advancedban.manager.PunishmentManager");
            Object instance = manager.getMethod("get").invoke(null);
            Method getPunishments = find(instance.getClass(), "getPunishments", "getLoadedPunishments");
            if (getPunishments == null) {
                logger.debug(() -> "AdvancedBan PunishmentManager has no list method");
                return rows;
            }
            Object list = getPunishments.getParameterTypes().length == 0
                    ? getPunishments.invoke(instance)
                    : getPunishments.invoke(instance, Boolean.TRUE);
            if (!(list instanceof Iterable<?>)) return rows;
            int n = 0;
            for (Object punishment : (Iterable<?>) list) {
                PunishmentImportRow row = map(punishment);
                if (row != null) {
                    rows.add(row);
                    n++;
                }
            }
            logger.info("AdvancedBan -> " + n + " rows");
        } catch (ClassNotFoundException absent) {
            logger.debug(() -> "AdvancedBan is not on the classpath");
        } catch (Throwable failed) {
            logger.warn("AdvancedBan import failed: " + failed);
        }
        return rows;
    }

    private static PunishmentImportRow map(Object punishment) {
        if (punishment == null) return null;
        String typeName = String.valueOf(call(punishment, "getType"));
        String type = mapType(typeName);
        if (type == null) return null;
        PunishmentImportRow row = new PunishmentImportRow();
        row.type = type;
        row.targetName = string(call(punishment, "getName"));
        row.targetUuid = string(call(punishment, "getUuid"));
        row.reason = string(call(punishment, "getReason"));
        row.issuedByName = string(call(punishment, "getOperator"));
        row.ip = string(call(punishment, "getIp"));
        Object start = call(punishment, "getStart");
        if (start instanceof Number) {
            row.issuedAtMillis = ((Number) start).longValue();
        }
        Object end = call(punishment, "getEnd");
        if (end instanceof Number) {
            long until = ((Number) end).longValue();
            if (until > 0 && row.issuedAtMillis > 0 && until > row.issuedAtMillis) {
                row.durationMinutes = Integer.valueOf((int) Math.max(1L, (until - row.issuedAtMillis) / 60000L));
            }
        }
        row.importProviderId = "advancedban:" + type + ":" + row.targetUuid + ":" + row.issuedAtMillis;
        row.active = true;
        return row;
    }

    private static String mapType(String typeName) {
        if (typeName == null) return null;
        String t = typeName.toUpperCase();
        if (t.contains("IP")) return "ipban";
        if (t.contains("BAN")) return "ban";
        if (t.contains("MUTE")) return "mute";
        if (t.contains("WARN")) return "warn";
        if (t.contains("KICK")) return "kick";
        return null;
    }

    private static Method find(Class<?> type, String... names) {
        for (int i = 0; i < names.length; i++) {
            try {
                return type.getMethod(names[i]);
            } catch (NoSuchMethodException e) {
                try {
                    return type.getMethod(names[i], boolean.class);
                } catch (NoSuchMethodException ignored) {
                    // next
                }
            }
        }
        return null;
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
