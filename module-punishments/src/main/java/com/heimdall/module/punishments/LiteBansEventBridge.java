package com.heimdall.module.punishments;

import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.punish.PunishmentIp;
import com.heimdall.core.punish.PunishmentParser;
import com.heimdall.core.punish.PunishmentText;
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
     *
     * <h2>Past the issue ceiling, a hooked punishment is sent as permanent</h2>
     *
     * <p>A LiteBans row is a length somebody else's plugin already accepted, and
     * {@code /ban Steve 100y} is a common way to spell "forever" there. The bot refuses anything
     * over {@link PunishmentParser#MAX_ISSUE_SECONDS}, so such a row was mirrored as a rejected
     * request and therefore not mirrored at all: the ban existed on the server and nowhere else.
     *
     * <p>Sending it with no length keys makes it a permanent ban on the bot's side, which is what
     * the operator meant. It is <strong>not</strong> a clamp: the native command path still
     * refuses a length past the ceiling rather than quietly changing it, because there a moderator
     * is present to be told. Here nobody is, the row is already live in LiteBans, and the choice
     * is between a permanent mirror and no mirror. One info line records that the length was
     * dropped, and what it was.
     */
    static void putHookDuration(Payload.Builder body, long startMillis, long endMillis,
            HeimdallLogger logger) {
        if (endMillis <= 0L || startMillis <= 0L || endMillis <= startMillis) {
            return;
        }
        long seconds = Math.max(1L, (endMillis - startMillis) / 1000L);
        if (seconds > PunishmentParser.MAX_ISSUE_SECONDS) {
            if (logger != null) {
                logger.info("LiteBans hook: a punishment set for "
                        + PunishmentText.compactDuration(Long.valueOf(seconds)) + " (" + seconds
                        + "s) is longer than the " + PunishmentParser.MAX_ISSUE_YEARS
                        + " years the bot accepts, so it is mirrored as permanent");
            }
            return;
        }
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
