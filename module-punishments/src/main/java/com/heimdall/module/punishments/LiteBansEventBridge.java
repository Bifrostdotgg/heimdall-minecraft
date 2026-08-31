package com.heimdall.module.punishments;

import com.heimdall.core.json.Payload;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.punish.PunishmentIp;
import com.heimdall.core.util.Registration;
import java.util.UUID;
import litebans.api.Entry;
import litebans.api.Events;

/**
 * Loaded only after {@code litebans.api.Events} is known to exist, so the rest of the module
 * still loads when LiteBans is absent.
 *
 * <p>Does not register {@code /ban}. Posts hashed issue/revoke rows to the bot. {@code --sender=}
 * is LiteBans' own flag and arrives here as {@link Entry#getExecutorName()}.
 */
final class LiteBansEventBridge {

    private LiteBansEventBridge() {
    }

    static Registration install(final ModuleContext context) {
        final Events.Listener listener = new Events.Listener() {
            @Override
            public void entryAdded(Entry entry) {
                postIssue(context, entry);
            }

            @Override
            public void entryRemoved(Entry entry) {
                postRevoke(context, entry);
            }
        };
        Events.get().register(listener);
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                try {
                    Events.get().unregister(listener);
                } catch (RuntimeException ignored) {
                    // LiteBans shutting down first.
                }
            }
        });
    }

    private static void postIssue(ModuleContext context, Entry entry) {
        if (entry == null || !context.api().isUsable()) return;
        PunishmentSettings settings = PunishmentSettings.from(context.config());
        String salt = settings.ipSalt;
        String type = mapType(entry);
        if (type == null) return;
        Payload.Builder body = Payload.builder()
                .put("type", type)
                .put("source", "hook")
                .put("opId", UUID.randomUUID().toString())
                .put("issuedAt", entry.getDateStart() > 0 ? entry.getDateStart() : System.currentTimeMillis())
                .put("silent", entry.isSilent())
                .put("reason", entry.getReason() == null ? "" : entry.getReason());
        if (entry.getUuid() != null && !entry.getUuid().isEmpty()) {
            body.put("targetUuid", dashedUuid(entry.getUuid()));
        }
        if (entry.getExecutorName() != null && !entry.getExecutorName().isEmpty()) {
            body.put("issuedByName", entry.getExecutorName());
        }
        if (entry.getExecutorUUID() != null && !entry.getExecutorUUID().isEmpty()) {
            body.put("issuedByUuid", dashedUuid(entry.getExecutorUUID()));
        }
        if ("ipban".equals(type)) {
            if (salt == null || salt.isEmpty()) {
                context.logger().warn("LiteBans hook skipped IP hash: ip salt has not been pushed");
                return;
            }
            String ip = entry.getIp();
            if (ip != null && !ip.isEmpty()) {
                body.put("ipDigest", PunishmentIp.hash(ip, salt));
            }
        }
        long until = entry.getDateEnd();
        long start = entry.getDateStart();
        if (until > 0 && start > 0 && until > start) {
            body.put("durationMinutes", (int) Math.max(1L, (until - start) / 60000L));
        }
        context.api().issuePunishment(body.build());
    }

    private static void postRevoke(ModuleContext context, Entry entry) {
        if (entry == null || !context.api().isUsable()) return;
        String type = mapType(entry);
        if (type == null) return;
        Payload.Builder body = Payload.builder()
                .put("type", type)
                .put("opId", UUID.randomUUID().toString())
                .put("issuedAt", System.currentTimeMillis())
                .put("revokedBy", entry.getExecutorName() == null ? "" : entry.getExecutorName());
        if (entry.getUuid() != null && !entry.getUuid().isEmpty()) {
            body.put("targetUuid", dashedUuid(entry.getUuid()));
        }
        PunishmentSettings settings = PunishmentSettings.from(context.config());
        if ("ipban".equals(type) && settings.ipSalt != null && !settings.ipSalt.isEmpty()
                && entry.getIp() != null && !entry.getIp().isEmpty()) {
            body.put("ipDigest", PunishmentIp.hash(entry.getIp(), settings.ipSalt));
        }
        context.api().revokePunishment(body.build());
    }

    private static String mapType(Entry entry) {
        String raw = entry.getType();
        if (raw == null) return null;
        String type = raw.toLowerCase();
        if ("ban".equals(type) && entry.isIpban()) return "ipban";
        if ("ban".equals(type) || "mute".equals(type) || "warn".equals(type) || "kick".equals(type)
                || "warning".equals(type)) {
            return "warning".equals(type) ? "warn" : type;
        }
        return null;
    }

    private static String dashedUuid(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.length() == 32) {
            return t.substring(0, 8) + "-" + t.substring(8, 12) + "-" + t.substring(12, 16)
                    + "-" + t.substring(16, 20) + "-" + t.substring(20);
        }
        return t;
    }
}
