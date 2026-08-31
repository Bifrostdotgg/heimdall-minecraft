package com.heimdall.module.punishments;

import com.heimdall.core.command.CommandHandler;
import com.heimdall.core.command.CommandSource;
import com.heimdall.core.command.CommandSpec;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.ApiError;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.mirror.MirrorPolicy;
import com.heimdall.core.mirror.MirrorStore;
import com.heimdall.core.module.HeimdallModule;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.CommandAttempt;
import com.heimdall.core.pipeline.LoginAttempt;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.punish.PunishmentIp;
import com.heimdall.core.punish.PunishmentParser;
import com.heimdall.core.remoteconfig.ModuleConfig;
import com.heimdall.core.remoteconfig.ModuleConfigListener;
import com.heimdall.core.session.PlayerSessionListener;
import com.heimdall.core.text.Msg;
import com.heimdall.core.tunnel.Capabilities;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import com.heimdall.core.util.Registration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;

/**
 * Native punishments: local mirror, login/chat/command gates, /hd ban family, durable outage queue.
 */
public final class HeimdallPunishmentsModule implements HeimdallModule {

    public static final String ID = "punishments";

    private volatile ModuleContext context;
    private volatile MirrorStore<ActivePunishment> mirror;
    private volatile PunishmentOutbox outbox;
    private volatile LastIpStore lastIps;
    private volatile GeoCountryLookup geo;
    private volatile long lastFullSyncAt;
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final AtomicBoolean flushAgain = new AtomicBoolean();
    private final List<Registration> aliasBinds = new CopyOnWriteArrayList<Registration>();

    static volatile HeimdallPunishmentsModule INSTANCE;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> capabilities() {
        return Collections.singleton(Capabilities.PUNISHMENTS);
    }

    @Override
    public Set<ServerRole> roles() {
        return Collections.emptySet();
    }

    @Override
    public void enable(ModuleContext context) {
        this.context = context;
        INSTANCE = this;
        this.mirror = MirrorStore.builder(
                context.logger(),
                context.platform().dataDirectory().resolve("punishments-mirror.json"),
                ActivePunishment.class)
                .policy(MirrorPolicy.builder()
                        .windowMs(TimeUnit.HOURS.toMillis(24))
                        .maxExtensionMs(TimeUnit.HOURS.toMillis(24))
                        .saveDebounceMs(500)
                        .build())
                .scheduler(context.executors().scheduler())
                .open();
        this.outbox = new PunishmentOutbox(
                context.logger(),
                context.platform().dataDirectory().resolve("punishments-outbox.json"));
        ServerRole role = context.platform().role();
        if (role == ServerRole.GATEKEEPER || role == ServerRole.STANDALONE) {
            this.lastIps = new LastIpStore(
                    context.logger(),
                    context.platform().dataDirectory().resolve("punishments-last-ip.json"));
        }
        this.geo = new GeoCountryLookup(context.logger(), context.platform().dataDirectory());

        context.interceptLogin(this::onLogin, 50);
        boolean backend = role == ServerRole.ENFORCER || role == ServerRole.STANDALONE;
        if (backend) {
            context.interceptChat(this::onChat, 50);
            context.interceptCommand(this::onCommand, 50);
        }
        context.onPlayerJoin(new PlayerSessionListener() {
            @Override
            public void onPlayerSession(PlayerHandle player, long timestampMs) {
                notifyWarn(player);
            }
        });
        context.tunnel().subscribe("punish.apply", applyHandler());
        context.tunnel().subscribe("punish.revoke", revokeHandler());
        context.tunnel().subscribe("punish.import", importHandler());
        context.onConfigChanged(new ModuleConfigListener() {
            @Override
            public void onModuleConfigChanged(String moduleId, ModuleConfig previous, ModuleConfig current) {
                ModuleContext ctx = HeimdallPunishmentsModule.this.context;
                if (ctx == null) return;
                ctx.platform().mainThread().execute(new Runnable() {
                    @Override
                    public void run() {
                        rebindAliases();
                    }
                });
            }
        });
        context.scheduleRepeating(new Runnable() {
            @Override
            public void run() {
                ModuleContext ctx = HeimdallPunishmentsModule.this.context;
                if (ctx == null) return;
                ctx.executors().io().execute(new Runnable() {
                    @Override
                    public void run() {
                        flushQueue();
                        LastIpStore ips = lastIps;
                        if (ips != null) {
                            ips.flush();
                        }
                    }
                });
            }
        }, 2_000, 5_000);
        context.scheduleRepeating(new Runnable() {
            @Override
            public void run() {
                ModuleContext ctx = HeimdallPunishmentsModule.this.context;
                if (ctx == null) return;
                ctx.executors().io().execute(new Runnable() {
                    @Override
                    public void run() {
                        sync(false);
                    }
                });
            }
        }, 5_000, TimeUnit.MINUTES.toMillis(5));
        rebindAliases();
        context.executors().io().execute(new Runnable() {
            @Override
            public void run() {
                flushQueue();
                sync(true);
            }
        });
        LiteBansSupport.tryHook(this, context);
    }

    @Override
    public void disable() {
        if (lastIps != null) {
            lastIps.flush();
            lastIps = null;
        }
        if (mirror != null) {
            try {
                mirror.close();
            } catch (Exception e) {
                if (context != null) {
                    context.logger().error("closing punishment mirror", e);
                }
            }
            mirror = null;
        }
        LiteBansSupport.unhook();
        aliasBinds.clear();
        geo = null;
        outbox = null;
        if (INSTANCE == this) INSTANCE = null;
        context = null;
    }

    /**
     * Root {@code /ban} family is replace-mode only. Hook mode must not steal LiteBans' verbs.
     * Re-run when {@code config.push} flips {@code rootAliases} or {@code mode}.
     */
    private void rebindAliases() {
        ModuleContext ctx = this.context;
        if (ctx == null) return;
        for (int i = 0; i < aliasBinds.size(); i++) {
            aliasBinds.get(i).close();
        }
        aliasBinds.clear();
        PunishmentSettings settings = PunishmentSettings.from(ctx.config());
        if (!settings.replaceMode() || !settings.rootAliases) {
            return;
        }
        ServerRole role = ctx.platform().role();
        boolean proxy = role == ServerRole.GATEKEEPER || role == ServerRole.STANDALONE;
        boolean backend = role == ServerRole.ENFORCER || role == ServerRole.STANDALONE;
        if (proxy) {
            bind(ctx, "ban", "heimdall.punishments.ban", "ban");
            bind(ctx, "tempban", "heimdall.punishments.ban", "tempban");
            bind(ctx, "ipban", "heimdall.punishments.ipban", "ipban");
            bind(ctx, "unban", "heimdall.punishments.unban", "unban");
            bind(ctx, "kick", "heimdall.punishments.kick", "kick");
            bind(ctx, "warn", "heimdall.punishments.warn", "warn");
            bind(ctx, "unwarn", "heimdall.punishments.unban", "unwarn");
            bind(ctx, "history", "heimdall.punishments.history", "history");
            bind(ctx, "staffhistory", "heimdall.punishments.history", "staffhistory");
            bind(ctx, "banlist", "heimdall.punishments.history", "banlist");
            bind(ctx, "dupeip", "heimdall.punishments.dupeip", "dupeip");
            bind(ctx, "iphistory", "heimdall.punishments.dupeip", "iphistory");
            bind(ctx, "rollback", "heimdall.punishments.unban", "rollback");
        }
        if (backend) {
            bind(ctx, "mute", "heimdall.punishments.mute", "mute");
            bind(ctx, "tempmute", "heimdall.punishments.mute", "tempmute");
            bind(ctx, "unmute", "heimdall.punishments.unban", "unmute");
        }
    }

    private void bind(ModuleContext context, final String name, String permission, final String type) {
        Registration handle = context.registerCommand(CommandSpec.named(name)
                .permission(permission)
                .usage("/" + name + " <player> [duration] [reason]")
                .description("Heimdall punishment")
                .handler(new CommandHandler() {
                    @Override
                    public void execute(CommandSource source, List<String> args) {
                        onStaffCommand(source, type, args);
                    }
                })
                .build());
        aliasBinds.add(handle);
    }

    void onStaffCommand(CommandSource source, String type, List<String> args) {
        ModuleContext ctx = this.context;
        if (ctx == null) return;
        PunishmentSettings settings = PunishmentSettings.from(ctx.config());
        if (isLookup(type)) {
            lookup(source, type, args);
            return;
        }
        if (!settings.replaceMode()) {
            source.sendMessage(Msg.legacy("§eNative punishments are not in replace mode. "
                    + "Use LiteBans or switch mode in the dashboard."));
            return;
        }
        if ("unban".equals(type) || "unmute".equals(type) || "unwarn".equals(type)
                || "rollback".equals(type)) {
            if (args.isEmpty()) {
                source.sendMessage(Msg.legacy("§cUsage: /" + type + " <player> [reason]"));
                return;
            }
            String reason = args.size() > 1 ? join(args, 1) : "";
            revoke(source, type, args.get(0), reason);
            return;
        }
        PunishmentParser.Parsed parsed;
        try {
            parsed = PunishmentParser.parse(args);
        } catch (IllegalArgumentException e) {
            source.sendMessage(Msg.legacy("§cUsage: /" + type + " <player> [duration] [reason]"));
            return;
        }
        if (("tempban".equals(type) || "tempmute".equals(type)) && parsed.durationMinutes == null) {
            source.sendMessage(Msg.legacy("§cA duration is required for /" + type + "."));
            return;
        }
        String issueType = type;
        if ("tempban".equals(type)) issueType = "ban";
        if ("tempmute".equals(type)) issueType = "mute";
        boolean silent = parsed.silent || (settings.silentByDefault && !parsed.publicFlag);
        issue(source, issueType, parsed, silent);
    }

    private static boolean isLookup(String type) {
        return "history".equals(type) || "banlist".equals(type) || "staffhistory".equals(type)
                || "dupeip".equals(type) || "iphistory".equals(type);
    }

    private void issue(final CommandSource source, final String type, final PunishmentParser.Parsed parsed,
            final boolean silent) {
        final ModuleContext ctx = this.context;
        final PlayerHandle online = ctx.platform().players().byName(parsed.target).orElse(null);
        if (online != null) {
            submitIssue(source, type, online.uuid().toString(), online.name(), parsed, silent);
            return;
        }
        LastIpStore.PlayerIps seen = lastIps == null ? null : lastIps.byName(parsed.target);
        if (seen != null) {
            submitIssue(source, type, seen.uuid, seen.name, parsed, silent);
            return;
        }
        source.sendMessage(Msg.legacy("§eResolving §f" + parsed.target + "§e..."));
        ctx.api().resolveName(parsed.target).whenComplete((resolved, failure) -> {
            if (failure != null || resolved == null) {
                source.sendMessage(Msg.legacy("§cCould not resolve §f" + parsed.target
                        + "§c. Offline never-seen names need the bot."));
                return;
            }
            submitIssue(source, type, resolved.uuid(), resolved.username(), parsed, silent);
        });
    }

    private void submitIssue(CommandSource source, String type, String uuid, String name,
            PunishmentParser.Parsed parsed, boolean silent) {
        ModuleContext ctx = this.context;
        PunishmentSettings settings = PunishmentSettings.from(ctx.config());
        String ipDigest = null;
        if ("ipban".equals(type)) {
            String ip = lastIpOf(uuid);
            if (ip == null || ip.isEmpty()) {
                source.sendMessage(Msg.legacy("§cNo last address for §f" + name
                        + "§c. IP bans are issued from the proxy or a standalone server "
                        + "after that player has connected."));
                return;
            }
            if (settings.ipSalt.isEmpty()) {
                source.sendMessage(Msg.legacy("§cIP salt has not been pushed yet. Wait for config."));
                return;
            }
            ipDigest = PunishmentIp.hash(ip, settings.ipSalt);
        }
        long now = System.currentTimeMillis();
        String opId = UUID.randomUUID().toString();
        ActivePunishment local = new ActivePunishment();
        local.id = "local-" + opId;
        local.type = type;
        local.targetUuid = uuid;
        local.targetName = name;
        local.ipDigest = ipDigest;
        local.reason = parsed.reason;
        local.silent = silent;
        local.issuedAt = Instant.ofEpochMilli(now).toString();
        local.issuedByName = source.name();
        local.issuedByUuid = source.uuid() == null ? null : source.uuid().toString();
        if (parsed.durationMinutes != null) {
            local.expiresAt = Instant.ofEpochMilli(now)
                    .plusSeconds(parsed.durationMinutes.intValue() * 60L).toString();
        }
        String mirrorKey = keyFor(local);
        if (mirror != null && !"kick".equals(type) && mirrorKey != null) {
            mirror.record(mirrorKey, local);
        }
        applyLive(uuid, local, settings);
        Payload.Builder body = Payload.builder()
                .put("type", type)
                .put("targetUuid", uuid)
                .put("targetName", name)
                .put("reason", parsed.reason == null ? "" : parsed.reason)
                .put("silent", silent)
                .put("source", "command")
                .put("opId", opId)
                .put("issuedAt", now);
        if (parsed.durationMinutes != null) {
            body.put("durationMinutes", parsed.durationMinutes.intValue());
        }
        if (ipDigest != null) {
            body.put("ipDigest", ipDigest);
        }
        if (source.uuid() != null) {
            body.put("issuedByUuid", source.uuid().toString());
        }
        if (source.name() != null) {
            body.put("issuedByName", source.name());
        }
        enqueue("issue", opId, now, body.build());
        source.sendMessage(Msg.legacy("§a" + type + " issued for §f" + name));
        flushSoon();
    }

    private void revoke(final CommandSource source, final String type, final String target,
            final String reason) {
        ModuleContext ctx = this.context;
        PlayerHandle online = ctx.platform().players().byName(target).orElse(null);
        if (online != null) {
            submitRevoke(source, type, online.uuid().toString(), online.name(), reason);
            return;
        }
        LastIpStore.PlayerIps seen = lastIps == null ? null : lastIps.byName(target);
        if (seen != null) {
            submitRevoke(source, type, seen.uuid, seen.name, reason);
            return;
        }
        source.sendMessage(Msg.legacy("§eResolving §f" + target + "§e..."));
        ctx.api().resolveName(target).whenComplete((resolved, failure) -> {
            if (failure != null || resolved == null) {
                ActivePunishment local = findByName(target, revokeTypes(type));
                if (local != null) {
                    submitRevoke(source, type, local.targetUuid, local.targetName, reason);
                    return;
                }
                source.sendMessage(Msg.legacy("§cCould not resolve §f" + target));
                return;
            }
            submitRevoke(source, type, resolved.uuid(), resolved.username(), reason);
        });
    }

    private void submitRevoke(CommandSource source, String type, String uuid, String name,
            String reason) {
        List<ActivePunishment> matches;
        if ("rollback".equals(type)) {
            ActivePunishment latest = latestActive(uuid);
            matches = latest == null
                    ? Collections.<ActivePunishment>emptyList()
                    : Collections.singletonList(latest);
        } else {
            matches = activeOf(uuid, revokeTypes(type));
        }
        if (matches.isEmpty()) {
            source.sendMessage(Msg.legacy("§eNo active " + typeLabel(type) + " for §f" + name));
            return;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < matches.size(); i++) {
            ActivePunishment punishment = matches.get(i);
            String key = keyFor(punishment);
            if (mirror != null && key != null) {
                mirror.evict(key);
            }
            String opId = UUID.randomUUID().toString();
            Payload.Builder body = Payload.builder()
                    .put("opId", opId)
                    .put("issuedAt", now)
                    .put("type", punishment.type)
                    .put("targetUuid", uuid)
                    .put("reason", reason == null ? "" : reason)
                    .put("revokedBy", source.name() == null ? "" : source.name());
            if (punishment.id != null && !punishment.id.startsWith("local-")) {
                body.put("id", punishment.id);
            }
            if (punishment.ipDigest != null) {
                body.put("ipDigest", punishment.ipDigest);
            }
            enqueue("revoke", opId, now, body.build());
        }
        source.sendMessage(Msg.legacy("§aRevoked " + matches.size() + " punishment(s) for §f" + name));
        flushSoon();
    }

    private static String[] revokeTypes(String type) {
        if ("unban".equals(type)) return new String[] {"ban", "ipban"};
        if ("unmute".equals(type)) return new String[] {"mute"};
        if ("unwarn".equals(type)) return new String[] {"warn"};
        return new String[] {"ban", "ipban", "mute", "warn"};
    }

    private static String typeLabel(String type) {
        if ("unban".equals(type)) return "ban";
        if ("unmute".equals(type)) return "mute";
        if ("unwarn".equals(type)) return "warn";
        if ("rollback".equals(type)) return "punishment";
        return type;
    }

    private Verdict onLogin(LoginAttempt attempt) {
        if (lastIps != null && attempt.ipAddress() != null && !attempt.ipAddress().isEmpty()) {
            lastIps.record(attempt.uuid().toString(), attempt.username(), attempt.ipAddress(),
                    System.currentTimeMillis());
        }
        if (mirror == null) return Verdict.abstain();
        PunishmentSettings settings = PunishmentSettings.from(context.config());
        if (!settings.replaceMode()) return Verdict.abstain();
        ActivePunishment ban = mirror.get("ban:" + attempt.uuid().toString());
        if (ban != null && !ban.expired(System.currentTimeMillis())) {
            return Verdict.deny(render(settings.banScreen, ban, settings));
        }
        if (shouldCheckIpBan() && attempt.ipAddress() != null && !attempt.ipAddress().isEmpty()
                && !settings.ipSalt.isEmpty()) {
            String digest = PunishmentIp.hash(attempt.ipAddress(), settings.ipSalt);
            ActivePunishment ipban = mirror.get("ipban:" + digest);
            if (ipban != null && !ipban.expired(System.currentTimeMillis())) {
                return Verdict.deny(render(settings.banScreen, ipban, settings));
            }
        }
        ServerRole role = context.platform().role();
        if ((role == ServerRole.GATEKEEPER || role == ServerRole.STANDALONE)
                && attempt.ipAddress() != null && !attempt.ipAddress().isEmpty()) {
            Verdict geoDeny = geoDeny(attempt.ipAddress(), settings);
            if (geoDeny != null) return geoDeny;
            Verdict subnetDeny = subnetDeny(attempt.ipAddress(), settings);
            if (subnetDeny != null) return subnetDeny;
        }
        return Verdict.abstain();
    }

    private boolean shouldCheckIpBan() {
        ServerRole role = context.platform().role();
        if (role != ServerRole.ENFORCER) {
            return true;
        }
        return context.platform().forwardsPlayerIps();
    }

    private Verdict onChat(ChatMessage message) {
        if (mirror == null) return Verdict.abstain();
        PunishmentSettings settings = PunishmentSettings.from(context.config());
        if (!settings.replaceMode()) return Verdict.abstain();
        if (isFrozen(message.senderUuid())) {
            return Verdict.deny(Msg.legacy("§cYou are frozen."));
        }
        ActivePunishment mute = mirror.get("mute:" + message.senderUuid());
        if (mute != null && !mute.expired(System.currentTimeMillis())) {
            return Verdict.deny(render(settings.muteScreen, mute, settings));
        }
        return Verdict.abstain();
    }

    private Verdict onCommand(CommandAttempt attempt) {
        if (mirror == null) return Verdict.abstain();
        PunishmentSettings settings = PunishmentSettings.from(context.config());
        if (!settings.replaceMode()) return Verdict.abstain();
        if (isFrozen(attempt.senderUuid())) {
            return Verdict.deny(Msg.legacy("§cYou are frozen."));
        }
        ActivePunishment mute = mirror.get("mute:" + attempt.senderUuid());
        if (mute == null || mute.expired(System.currentTimeMillis())) return Verdict.abstain();
        if (settings.blockedCommands.contains(attempt.label())) {
            return Verdict.deny(render(settings.muteScreen, mute, settings));
        }
        return Verdict.abstain();
    }

    private void notifyWarn(PlayerHandle player) {
        if (player == null || mirror == null || context == null) return;
        PunishmentSettings settings = PunishmentSettings.from(context.config());
        if (!settings.replaceMode()) return;
        ActivePunishment warn = mirror.get("warn:" + player.uuid());
        if (warn == null || warn.expired(System.currentTimeMillis())) return;
        player.sendMessage(render(settings.warnScreen, warn, settings));
    }

    private TunnelMessageHandler applyHandler() {
        return new TunnelMessageHandler() {
            @Override
            public void onMessage(Envelope envelope) {
                Payload payload = envelope.payload();
                ActivePunishment p = fromPayload(payload);
                if (p == null || mirror == null) return;
                if ("kick".equals(p.type)) {
                    applyLive(p.targetUuid, p, PunishmentSettings.from(context.config()));
                    return;
                }
                String key = keyFor(p);
                if (key == null) return;
                mirror.record(key, p);
                applyLive(p.targetUuid, p, PunishmentSettings.from(context.config()));
            }
        };
    }

    private TunnelMessageHandler importHandler() {
        return new TunnelMessageHandler() {
            @Override
            public void onMessage(Envelope envelope) {
                ModuleContext ctx = HeimdallPunishmentsModule.this.context;
                if (ctx == null) return;
                LiteBansSupport.importNow(ctx);
            }
        };
    }

    private TunnelMessageHandler revokeHandler() {
        return new TunnelMessageHandler() {
            @Override
            public void onMessage(Envelope envelope) {
                if (mirror == null) return;
                Payload payload = envelope.payload();
                String type = payload.string("type", "");
                String uuid = payload.string("targetUuid", "");
                String digest = payload.string("ipDigest", "");
                if ("ipban".equals(type) && !digest.isEmpty()) {
                    mirror.evict("ipban:" + digest);
                } else if (!uuid.isEmpty() && !type.isEmpty()) {
                    mirror.evict(type + ":" + uuid);
                }
            }
        };
    }

    private void flushSoon() {
        ModuleContext ctx = this.context;
        if (ctx == null) return;
        flushAgain.set(true);
        ctx.executors().io().execute(new Runnable() {
            @Override
            public void run() {
                flushQueue();
            }
        });
    }

    /**
     * Uploads queued writes. A second call while one is in flight is remembered and run after,
     * rather than dropped.
     */
    void flushQueue() {
        if (!flushing.compareAndSet(false, true)) {
            flushAgain.set(true);
            return;
        }
        try {
            do {
                flushAgain.set(false);
                flushQueueLocked();
            } while (flushAgain.get());
        } finally {
            flushing.set(false);
        }
        if (flushAgain.get()) {
            flushQueue();
        }
    }

    private void flushQueueLocked() {
        ModuleContext ctx = this.context;
        PunishmentOutbox box = this.outbox;
        if (ctx == null || box == null || box.isEmpty() || !ctx.api().isUsable()) return;
        boolean uploaded = false;
        List<PunishmentOutbox.Entry> pending = box.snapshot();
        for (int i = 0; i < pending.size(); i++) {
            PunishmentOutbox.Entry entry = pending.get(i);
            try {
                if ("issue".equals(entry.op)) {
                    Payload result = ctx.api().issuePunishment(entry.payload)
                            .get(ctx.api().settings().overallTimeoutMs(), TimeUnit.MILLISECONDS);
                    rememberIssuedId(entry.payload, result);
                    box.remove(entry.opId);
                    uploaded = true;
                } else if ("revoke".equals(entry.op)) {
                    Payload result = ctx.api().revokePunishment(entry.payload)
                            .get(ctx.api().settings().overallTimeoutMs(), TimeUnit.MILLISECONDS);
                    if (result != null && result.intValue("revoked", 1) == 0) {
                        box.remove(entry.opId);
                        continue;
                    }
                    box.remove(entry.opId);
                    uploaded = true;
                } else {
                    box.remove(entry.opId);
                }
            } catch (Exception e) {
                Throwable cause = e instanceof ExecutionException && e.getCause() != null
                        ? e.getCause()
                        : e;
                if (isGone(cause)) {
                    ctx.logger().warn("punishment outbox dropped " + entry.op + " " + entry.opId
                            + " (already gone): " + cause.getMessage());
                    box.remove(entry.opId);
                    continue;
                }
                if (cause instanceof ApiError) {
                    ctx.logger().debug(() -> "punishment outbox holding " + entry.opId
                            + ": " + cause.getMessage());
                    continue;
                }
                ctx.logger().debug(() -> "punishment outbox upload deferred: " + cause.getMessage());
                break;
            }
        }
        if (uploaded) {
            sync(true);
        }
    }

    /**
     * Drop only when the bot says that selector is already gone. HMAC 401, 400, 403 and 5xx hold
     * the row so a later successful flush can still send it, and so a following ETag GET cannot
     * resurrect a local unban.
     */
    static boolean isGone(Throwable cause) {
        if (!(cause instanceof ApiError)) {
            return false;
        }
        ApiError error = (ApiError) cause;
        if (error.httpStatus() != 404) {
            return false;
        }
        String code = error.code() == null ? "" : error.code().toUpperCase(Locale.ROOT);
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        return code.contains("NOT_FOUND")
                || message.contains("not found")
                || message.contains("no active")
                || message.contains("gone");
    }

    private void rememberIssuedId(Payload request, Payload result) {
        if (mirror == null || result == null) return;
        String id = result.string("id", "");
        if (id.isEmpty()) id = result.string("_id", "");
        if (id.isEmpty()) return;
        ActivePunishment local = new ActivePunishment();
        local.id = id;
        local.type = request.string("type", result.string("type", ""));
        local.targetUuid = request.string("targetUuid", result.string("targetUuid", null));
        local.targetName = request.string("targetName", result.string("targetName", null));
        local.ipDigest = request.string("ipDigest", result.string("ipDigest", null));
        local.reason = request.string("reason", result.string("reason", ""));
        local.silent = request.bool("silent", false);
        if (local.type.isEmpty() || "kick".equals(local.type)) return;
        String key = keyFor(local);
        if (key == null) return;
        ActivePunishment existing = mirror.get(key);
        if (existing != null) {
            existing.id = id;
            mirror.record(key, existing);
        }
    }

    private void sync(boolean force) {
        ModuleContext ctx = this.context;
        MirrorStore<ActivePunishment> store = this.mirror;
        if (ctx == null || store == null || !ctx.api().isUsable()) return;
        if (!force && lastFullSyncAt != 0
                && System.currentTimeMillis() - lastFullSyncAt < TimeUnit.MINUTES.toMillis(4)) {
            return;
        }
        ctx.api().punishmentSync(store.lastEtag()).whenComplete((data, failure) -> {
            if (failure != null || data == null) return;
            try {
                java.util.Map<String, ActivePunishment> authoritative =
                        new java.util.LinkedHashMap<String, ActivePunishment>();
                for (Payload row : data.children("punishments")) {
                    ActivePunishment p = fromPayload(row);
                    if (p == null || "kick".equals(p.type)) continue;
                    String key = keyFor(p);
                    if (key == null) continue;
                    ActivePunishment existing = store.get(key);
                    if (existing != null) {
                        if (p.issuedAt == null || p.issuedAt.isEmpty()) {
                            p.issuedAt = existing.issuedAt;
                        }
                        if (p.issuedByName == null || p.issuedByName.isEmpty()) {
                            p.issuedByName = existing.issuedByName;
                        }
                    }
                    authoritative.put(key, p);
                }
                store.reconcile(authoritative);
                String hash = data.string("hash", "");
                if (!hash.isEmpty()) {
                    store.setLastEtag(hash);
                }
                lastFullSyncAt = System.currentTimeMillis();
                replayPendingWrites();
            } catch (RuntimeException e) {
                ctx.logger().error("punishment sync apply failed", e);
            }
        });
    }

    private void applyLive(String uuid, ActivePunishment punishment, PunishmentSettings settings) {
        if (uuid == null || context == null || punishment == null) return;
        PlayerHandle player = context.platform().players().byUuid(parseUuid(uuid)).orElse(null);
        if (player == null) return;
        if ("warn".equals(punishment.type)) {
            player.sendMessage(render(settings.warnScreen, punishment, settings));
            return;
        }
        if (!"ban".equals(punishment.type) && !"ipban".equals(punishment.type)
                && !"kick".equals(punishment.type)) {
            return;
        }
        ServerRole role = context.platform().role();
        if (role == ServerRole.ENFORCER && !"kick".equals(punishment.type)) {
            return;
        }
        String screen = "kick".equals(punishment.type) ? settings.kickScreen : settings.banScreen;
        player.kick(render(screen, punishment, settings));
    }

    private void lookup(final CommandSource source, final String type, final List<String> args) {
        if ("banlist".equals(type)) {
            showBanlist(source);
            return;
        }
        if ("iphistory".equals(type)) {
            showIpHistory(source, args);
            return;
        }
        if ("dupeip".equals(type)) {
            showDupeip(source, args);
            return;
        }
        if (args.isEmpty()) {
            source.sendMessage(Msg.legacy("§cUsage: /" + type + " <player>"));
            return;
        }
        final String target = args.get(0);
        if ("staffhistory".equals(type)) {
            showStaffHistory(source, target);
            return;
        }
        PlayerHandle online = context.platform().players().byName(target).orElse(null);
        if (online != null) {
            showHistory(source, online.uuid().toString(), online.name());
            return;
        }
        LastIpStore.PlayerIps seen = lastIps == null ? null : lastIps.byName(target);
        if (seen != null) {
            showHistory(source, seen.uuid, seen.name);
            return;
        }
        source.sendMessage(Msg.legacy("§eResolving §f" + target + "§e..."));
        context.api().resolveName(target).whenComplete((resolved, failure) -> {
            if (failure != null || resolved == null) {
                ActivePunishment local = findByName(target, new String[] {"ban", "ipban", "mute", "warn"});
                if (local != null) {
                    showHistory(source, local.targetUuid, local.targetName);
                    return;
                }
                source.sendMessage(Msg.legacy("§cCould not resolve §f" + target));
                return;
            }
            showHistory(source, resolved.uuid(), resolved.username());
        });
    }

    private void showHistory(final CommandSource source, final String uuid, final String name) {
        final List<String> lines = new ArrayList<String>();
        lines.add("§6History for §f" + name);
        if (context.api().isUsable()) {
            context.api().playerPunishments(uuid).whenComplete((data, failure) -> {
                if (failure != null || data == null) {
                    source.sendMessage(Msg.legacy(joinLines(localHistory(uuid, name))));
                    return;
                }
                List<Payload> rows = data.children("punishments");
                if (rows.isEmpty()) {
                    source.sendMessage(Msg.legacy(joinLines(localHistory(uuid, name))));
                    return;
                }
                for (int i = 0; i < rows.size() && i < 20; i++) {
                    lines.add(formatRow(rows.get(i)));
                }
                source.sendMessage(Msg.legacy(joinLines(lines)));
            });
            return;
        }
        source.sendMessage(Msg.legacy(joinLines(localHistory(uuid, name))));
    }

    private List<String> localHistory(String uuid, String name) {
        List<String> lines = new ArrayList<String>();
        lines.add("§6History for §f" + name + " §8(local mirror)");
        List<ActivePunishment> found = activeOf(uuid, new String[] {"ban", "ipban", "mute", "warn"});
        if (found.isEmpty()) {
            lines.add("§7No active punishments on this instance.");
            return lines;
        }
        for (int i = 0; i < found.size(); i++) {
            lines.add(formatLocal(found.get(i)));
        }
        return lines;
    }

    private void showBanlist(CommandSource source) {
        List<String> lines = new ArrayList<String>();
        lines.add("§6Active bans");
        int n = 0;
        if (mirror != null) {
            for (String key : mirror.keys()) {
                ActivePunishment p = mirror.get(key);
                if (p == null || p.expired(System.currentTimeMillis())) continue;
                if (!"ban".equals(p.type) && !"ipban".equals(p.type)) continue;
                lines.add(formatLocal(p));
                n++;
            }
        }
        if (n == 0) {
            lines.add("§7None on this instance.");
        }
        source.sendMessage(Msg.legacy(joinLines(lines)));
        final int localCount = n;
        if (context.api().isUsable()) {
            context.api().listPunishments("ban", null, Boolean.TRUE).whenComplete((data, failure) -> {
                if (failure != null || data == null) return;
                int extra = data.children("punishments").size();
                if (extra > localCount) {
                    source.sendMessage(Msg.legacy("§7Bot lists " + extra + " active bans network-wide."));
                }
            });
        }
    }

    private void showStaffHistory(final CommandSource source, final String staff) {
        if (!context.api().isUsable()) {
            source.sendMessage(Msg.legacy("§eStaff history needs the bot. Showing local issued-by matches."));
            List<String> lines = new ArrayList<String>();
            lines.add("§6Staff history for §f" + staff + " §8(local)");
            int n = 0;
            if (mirror != null) {
                for (String key : mirror.keys()) {
                    ActivePunishment p = mirror.get(key);
                    if (p == null || p.issuedByName == null) continue;
                    if (!staff.equalsIgnoreCase(p.issuedByName)) continue;
                    lines.add(formatLocal(p));
                    n++;
                }
            }
            if (n == 0) lines.add("§7None on this instance.");
            source.sendMessage(Msg.legacy(joinLines(lines)));
            return;
        }
        context.api().listPunishments(null, null, null).whenComplete((data, failure) -> {
            List<String> lines = new ArrayList<String>();
            lines.add("§6Staff history for §f" + staff);
            int n = 0;
            if (data != null) {
                for (Payload row : data.children("punishments")) {
                    String by = row.string("issuedByName", "");
                    if (!staff.equalsIgnoreCase(by)) continue;
                    lines.add(formatRow(row));
                    n++;
                    if (n >= 20) break;
                }
            }
            if (n == 0) lines.add("§7No matching punishments.");
            source.sendMessage(Msg.legacy(joinLines(lines)));
        });
    }

    private void showDupeip(CommandSource source, List<String> args) {
        if (args.isEmpty()) {
            source.sendMessage(Msg.legacy("§cUsage: /dupeip <player>"));
            return;
        }
        if (lastIps == null) {
            source.sendMessage(Msg.legacy("§e/dupeip reads this proxy's last-address file. "
                    + "Run it on the gatekeeper or a standalone server."));
            return;
        }
        String target = args.get(0);
        LastIpStore.PlayerIps player = lastIps.byName(target);
        if (player == null) {
            PlayerHandle online = context.platform().players().byName(target).orElse(null);
            if (online != null) {
                player = lastIps.get(online.uuid().toString());
            }
        }
        if (player == null || player.lastIp == null) {
            source.sendMessage(Msg.legacy("§eNo last address recorded for §f" + target));
            return;
        }
        List<LastIpStore.PlayerIps> alts = lastIps.sharing(player.lastIp);
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < alts.size(); i++) {
            LastIpStore.PlayerIps alt = alts.get(i);
            if (alt.name != null && !alt.name.isEmpty()) {
                names.add(alt.name);
            }
        }
        source.sendMessage(Msg.legacy("§6Accounts sharing §f" + target + "§6's last address §7("
                + names.size() + ")"));
        if (names.isEmpty()) {
            source.sendMessage(Msg.legacy("§7None recorded."));
        } else {
            source.sendMessage(Msg.legacy("§7 " + joinNames(names)));
        }
    }

    private void showIpHistory(CommandSource source, List<String> args) {
        if (lastIps == null) {
            source.sendMessage(Msg.legacy("§e/iphistory is local to the proxy and standalone. "
                    + "Enforcer backends do not store addresses."));
            return;
        }
        if (args.isEmpty()) {
            source.sendMessage(Msg.legacy("§cUsage: /iphistory <player>"));
            return;
        }
        LastIpStore.PlayerIps player = lastIps.byName(args.get(0));
        if (player == null) {
            PlayerHandle online = context.platform().players().byName(args.get(0)).orElse(null);
            if (online != null) player = lastIps.get(online.uuid().toString());
        }
        if (player == null) {
            source.sendMessage(Msg.legacy("§eNo address history for §f" + args.get(0)));
            return;
        }
        List<String> lines = new ArrayList<String>();
        lines.add("§6Address history for §f" + (player.name == null ? args.get(0) : player.name));
        for (int i = player.history.size() - 1; i >= 0; i--) {
            LastIpStore.Sighting sight = player.history.get(i);
            lines.add("§7 - §f" + LastIpStore.obfuscate(sight.ip) + " §8" + Instant.ofEpochMilli(sight.seenAt));
        }
        source.sendMessage(Msg.legacy(joinLines(lines)));
    }

    private String lastIpOf(String uuid) {
        if (lastIps == null) return null;
        LastIpStore.PlayerIps row = lastIps.get(uuid);
        return row == null ? null : row.lastIp;
    }

    private List<ActivePunishment> activeOf(String uuid, String[] types) {
        List<ActivePunishment> out = new ArrayList<ActivePunishment>();
        if (mirror == null || uuid == null) return out;
        long now = System.currentTimeMillis();
        for (int i = 0; i < types.length; i++) {
            ActivePunishment p = mirror.get(types[i] + ":" + uuid);
            if (p != null && !p.expired(now)) out.add(p);
        }
        if (java.util.Arrays.asList(types).contains("ipban")) {
            for (String key : mirror.keys()) {
                if (!key.startsWith("ipban:")) continue;
                ActivePunishment p = mirror.get(key);
                if (p != null && uuid.equalsIgnoreCase(p.targetUuid) && !p.expired(now)
                        && !out.contains(p)) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    private ActivePunishment latestActive(String uuid) {
        List<ActivePunishment> all = activeOf(uuid, new String[] {"ban", "ipban", "mute", "warn"});
        ActivePunishment best = null;
        for (int i = 0; i < all.size(); i++) {
            ActivePunishment p = all.get(i);
            if (best == null || p.issuedAtMillis() >= best.issuedAtMillis()) {
                best = p;
            }
        }
        return best;
    }

    private ActivePunishment findByName(String name, String[] types) {
        if (mirror == null || name == null) return null;
        for (String key : mirror.keys()) {
            ActivePunishment p = mirror.get(key);
            if (p == null || p.targetName == null) continue;
            if (!name.equalsIgnoreCase(p.targetName)) continue;
            for (int i = 0; i < types.length; i++) {
                if (types[i].equals(p.type)) return p;
            }
        }
        return null;
    }

    private void enqueue(String op, String opId, long issuedAt, Payload payload) {
        PunishmentOutbox box = this.outbox;
        if (box == null) return;
        String serverId = "";
        try {
            serverId = context.api().settings().serverId();
        } catch (RuntimeException ignored) {
            serverId = "";
        }
        box.enqueue(new PunishmentOutbox.Entry(opId, issuedAt, serverId, op, payload));
    }

    /**
     * Re-applies unacked outbox rows onto the mirror after an ETag pull, so a local unban cannot
     * be resurrected by a stale full snapshot while the revoke is still queued.
     */
    void replayPendingWrites() {
        MirrorStore<ActivePunishment> store = this.mirror;
        PunishmentOutbox box = this.outbox;
        if (store == null || box == null) return;
        List<PunishmentOutbox.Entry> pending = box.snapshot();
        for (int i = 0; i < pending.size(); i++) {
            PunishmentOutbox.Entry entry = pending.get(i);
            if ("issue".equals(entry.op)) {
                ActivePunishment local = fromPayload(entry.payload);
                if (local == null || "kick".equals(local.type)) continue;
                String key = keyFor(local);
                if (key != null) {
                    store.record(key, local);
                }
            } else if ("revoke".equals(entry.op)) {
                evictFromPayload(entry.payload);
            }
        }
    }

    private void evictFromPayload(Payload payload) {
        if (mirror == null || payload == null) return;
        String type = payload.string("type", "");
        String uuid = payload.string("targetUuid", "");
        String digest = payload.string("ipDigest", "");
        if ("ipban".equals(type) && !digest.isEmpty()) {
            mirror.evict("ipban:" + digest);
        } else if (!uuid.isEmpty() && !type.isEmpty()) {
            mirror.evict(type + ":" + uuid);
        }
    }

    private Verdict geoDeny(String ip, PunishmentSettings settings) {
        GeoCountryLookup lookup = this.geo;
        if (lookup == null || !lookup.ready() || mirror == null) return null;
        String country = lookup.countryOf(ip);
        if (country == null || country.isEmpty()) return null;
        ActivePunishment geoBan = mirror.get("geo:" + country.toUpperCase(Locale.ROOT));
        if (geoBan != null && !geoBan.expired(System.currentTimeMillis())) {
            return Verdict.deny(render(settings.banScreen, geoBan, settings));
        }
        return null;
    }

    private Verdict subnetDeny(String ip, PunishmentSettings settings) {
        if (mirror == null) return null;
        long now = System.currentTimeMillis();
        for (String key : mirror.keys()) {
            if (!key.startsWith("subnet:")) continue;
            ActivePunishment p = mirror.get(key);
            if (p == null || p.expired(now)) continue;
            if (Cidr.matches(p.cidr, ip)) {
                return Verdict.deny(render(settings.banScreen, p, settings));
            }
        }
        return null;
    }

    public boolean isFrozen(UUID uuid) {
        if (uuid == null || mirror == null) return false;
        ActivePunishment freeze = mirror.get("freeze:" + uuid);
        return freeze != null && !freeze.expired(System.currentTimeMillis());
    }

    public boolean isMuted(UUID uuid) {
        if (uuid == null || mirror == null) return false;
        ActivePunishment mute = mirror.get("mute:" + uuid);
        return mute != null && !mute.expired(System.currentTimeMillis());
    }

    public void notifyStaff(String line) {
        ModuleContext ctx = this.context;
        if (ctx == null || line == null) return;
        Component message = Msg.legacy(line);
        for (PlayerHandle player : ctx.platform().players().onlinePlayers()) {
            if (player.hasPermission("heimdall.punishments.mute")
                    || player.hasPermission("heimdall.admin")) {
                player.sendMessage(message);
            }
        }
    }

    /**
     * Login looks up {@code ipban:<hmac>}. A digest-less ipban must not be stored under the UUID
     * or the gate can never hit it, and every player would share that miss.
     */
    static String keyFor(ActivePunishment p) {
        if (p == null || p.type == null || p.type.isEmpty()) return null;
        if ("ipban".equals(p.type)) {
            if (p.ipDigest == null || p.ipDigest.isEmpty()) return null;
            return "ipban:" + p.ipDigest;
        }
        if ("geo".equals(p.type)) {
            String cc = p.country != null && !p.country.isEmpty() ? p.country : p.targetName;
            if (cc == null || cc.isEmpty()) return null;
            return "geo:" + cc.toUpperCase(Locale.ROOT);
        }
        if ("subnet".equals(p.type)) {
            String cidr = Cidr.canonical(p.cidr != null && !p.cidr.isEmpty() ? p.cidr : p.targetName);
            if (cidr.isEmpty()) return null;
            p.cidr = cidr;
            return "subnet:" + cidr;
        }
        if (p.targetUuid == null || p.targetUuid.isEmpty()) return null;
        return p.type + ":" + p.targetUuid;
    }

    static ActivePunishment fromPayload(Payload payload) {
        ActivePunishment p = new ActivePunishment();
        p.id = payload.string("id", "");
        if (p.id.isEmpty()) p.id = payload.string("_id", "");
        p.type = payload.string("type", "");
        p.targetUuid = payload.string("targetUuid", null);
        p.targetName = payload.string("targetName", null);
        p.ipDigest = payload.string("ipDigest", null);
        p.reason = payload.string("reason", "");
        p.expiresAt = payload.string("expiresAt", null);
        p.issuedAt = issuedAtOf(payload);
        p.issuedByName = payload.string("issuedByName", null);
        p.issuedByUuid = payload.string("issuedByUuid", null);
        p.country = payload.string("country", null);
        p.cidr = payload.string("cidr", null);
        p.silent = payload.bool("silent", false);
        if (p.type.isEmpty()) return null;
        return p;
    }

    static String issuedAtOf(Payload payload) {
        String raw = payload.string("issuedAt", "");
        if (raw.isEmpty()) return null;
        if (raw.indexOf('-') >= 0 || raw.indexOf('T') >= 0) {
            return raw;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(raw)).toString();
        } catch (RuntimeException e) {
            return raw;
        }
    }

    static Component render(String template, ActivePunishment p, PunishmentSettings settings) {
        String reason = p.reason == null ? "" : p.reason;
        String player = p.targetName == null ? "" : p.targetName;
        String appeal = settings == null || settings.appealUrl == null ? "" : settings.appealUrl;
        return Msg.miniTemplate(template,
                "reason", reason,
                "player", player,
                "appeal_url", appeal);
    }

    private static String formatLocal(ActivePunishment p) {
        String who = p.targetName == null ? "?" : p.targetName;
        String by = p.issuedByName == null || p.issuedByName.isEmpty() ? "" : " §7by §f" + p.issuedByName;
        String reason = p.reason == null || p.reason.isEmpty() ? "" : " §8" + p.reason;
        return "§7 - §c" + p.type + " §f" + who + by + reason;
    }

    private static String formatRow(Payload row) {
        String type = row.string("type", "?");
        String who = row.string("targetName", "?");
        String by = row.string("issuedByName", "");
        String reason = row.string("reason", "");
        boolean active = row.bool("active", false);
        String line = "§7 - §c" + type + " §f" + who;
        if (!by.isEmpty()) line += " §7by §f" + by;
        if (!reason.isEmpty()) line += " §8" + reason;
        if (!active) line += " §8(revoked)";
        return line;
    }

    private static String joinLines(List<String> lines) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) b.append('\n');
            b.append(lines.get(i));
        }
        return b.toString();
    }

    private static String joinNames(List<String> names) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) b.append("§7, §f");
            b.append(names.get(i));
        }
        return b.toString();
    }

    private static String join(List<String> parts, int from) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < parts.size(); i++) {
            if (b.length() > 0) b.append(' ');
            b.append(parts.get(i));
        }
        return b.toString();
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException e) {
            return new UUID(0, 0);
        }
    }

    public boolean isReplaceMode() {
        ModuleContext ctx = this.context;
        return ctx != null && PunishmentSettings.from(ctx.config()).replaceMode();
    }

    public HeimdallLogger logger() {
        return context == null ? null : context.logger();
    }

    public HeimdallApi api() {
        return context == null ? null : context.api();
    }

    MirrorStore<ActivePunishment> mirrorForTest() {
        return mirror;
    }

    PunishmentOutbox outboxForTest() {
        return outbox;
    }

    LastIpStore lastIpsForTest() {
        return lastIps;
    }
}
