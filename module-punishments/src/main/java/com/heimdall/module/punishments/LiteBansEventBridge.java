package com.heimdall.module.punishments;

import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
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
        Payload body = issueBody(entry, settings.ipSalt, context.logger());
        if (body == null) return;
        context.api().issuePunishment(body);
    }

    /**
     * The issue payload for one LiteBans entry, or {@code null} when there is nothing to send.
     *
     * <p>Split out of {@link #postIssue} so the wire shape can be asserted without a running
     * LiteBans or a socket. What is most likely to be wrong here is the duration, and reading a
     * length back off a payload needs neither a server nor a network.
     */
    static Payload issueBody(Entry entry, String salt, HeimdallLogger logger) {
        String type = mapType(entry);
        if (type == null) return null;
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
                logger.warn("LiteBans hook skipped IP hash: ip salt has not been pushed");
                return null;
            }
            String ip = entry.getIp();
            if (ip != null && !ip.isEmpty()) {
                body.put("ipDigest", PunishmentIp.hash(ip, salt));
            }
        }
        putHookDuration(body, entry.getDateStart(), entry.getDateEnd(), logger);
        return body.build();
    }

    /**
     * Writes the length of a hooked LiteBans punishment, in seconds, or nothing at all.
     *
     * <p>Seconds rather than minutes: LiteBans stores milliseconds, and rounding a short mute up
     * to the minute was losing a punishment somebody deliberately set.
     */
    static void putHookDuration(Payload.Builder body, long startMillis, long endMillis,
            HeimdallLogger logger) {
        if (endMillis <= 0L || startMillis <= 0L || endMillis <= startMillis) {
            return;
        }
        long seconds = Math.max(1L, (endMillis - startMillis) / 1000L);
        HeimdallPunishmentsModule.putDuration(body, seconds);
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
