package com.heimdall.module.punishments;

import com.heimdall.core.http.HeimdallApi;
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
 *
 * <p><strong>Through the module's outbox, never straight to the bot.</strong> These used to be
 * fire-and-forget calls, so a ban or a lift made in LiteBans while the bot was unreachable never
 * reached Heimdall at all. A missed lift is the dangerous one: the Heimdall row stays active, and
 * the day the guild moves to replace mode Heimdall bans the player again. The outbox is durable on
 * disk and retried, which is what the plugin's own commands already relied on.
 */
final class LiteBansEventBridge {

    private LiteBansEventBridge() {
    }

    static Registration install(final ModuleContext context) {
        return install(context, HeimdallPunishmentsModule.INSTANCE);
    }

    static Registration install(final ModuleContext context, final HeimdallPunishmentsModule module) {
        final Events.Listener listener = new Events.Listener() {
            @Override
            public void entryAdded(Entry entry) {
                postIssue(context, module, entry);
            }

            @Override
            public void entryRemoved(Entry entry) {
                postRevoke(context, module, entry);
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

    private static void postIssue(ModuleContext context, HeimdallPunishmentsModule module, Entry entry) {
        if (entry == null || !configured(context)) return;
        PunishmentSettings settings = PunishmentSettings.from(context.config());
        Payload body = issueBody(entry, settings.ipSalt, context.logger());
        if (body == null) return;
        send(context, module, "issue", body);
    }

    /**
     * A server with no bot configured has nowhere to send these, ever, so nothing is queued for
     * one. A bot still being discovered, or unreachable, is exactly what the queue is for.
     */
    private static boolean configured(ModuleContext context) {
        return context.api().availability() != HeimdallApi.Availability.NOT_CONFIGURED;
    }

    /** Queues one write in the module's outbox; straight to the bot only if the module has gone. */
    private static void send(ModuleContext context, HeimdallPunishmentsModule module, String op,
            Payload body) {
        if (module != null && module.queueHookWrite(op, body)) return;
        if (!context.api().isUsable()) return;
        if ("issue".equals(op)) {
            context.api().issuePunishment(body);
        } else {
            context.api().revokePunishment(body);
        }
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

    private static void postRevoke(ModuleContext context, HeimdallPunishmentsModule module, Entry entry) {
        if (entry == null || !configured(context)) return;
        PunishmentSettings settings = PunishmentSettings.from(context.config());
        Payload body = revokeBody(entry, settings.ipSalt);
        if (body == null) return;
        send(context, module, "revoke", body);
    }

    /**
     * The revoke payload for one removed LiteBans entry, or {@code null} when there is nothing to
     * send: a type the bot does not mirror, or the removal our own lift caused
     * ({@link LiteBansRevokeLift}). The bot already revoked the row that lift was about, and a
     * loose type-and-uuid revoke would sweep any other row of that type on the player, hidden ones
     * too. Split out of {@link #postRevoke} for the same reason as {@link #issueBody}.
     */
    static Payload revokeBody(Entry entry, String salt) {
        String type = mapType(entry);
        if (type == null) return null;
        if (LiteBansRevokeLift.isOwnEcho(type, dashedUuid(entry.getUuid()))) return null;
        Payload.Builder body = Payload.builder()
                .put("type", type)
                // Tells the bot LiteBans lifted this and has announced it, so the revoke it fans
                // out is not announced a second time. See
                // HeimdallPunishmentsModule#liteBansAnnounced.
                .put("source", "hook")
                .put("opId", UUID.randomUUID().toString())
                .put("issuedAt", System.currentTimeMillis())
                .put("revokedBy", entry.getExecutorName() == null ? "" : entry.getExecutorName());
        if (entry.getUuid() != null && !entry.getUuid().isEmpty()) {
            body.put("targetUuid", dashedUuid(entry.getUuid()));
        }
        if ("ipban".equals(type) && salt != null && !salt.isEmpty()
                && entry.getIp() != null && !entry.getIp().isEmpty()) {
            body.put("ipDigest", PunishmentIp.hash(entry.getIp(), salt));
        }
        return body.build();
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
