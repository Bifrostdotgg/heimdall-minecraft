package com.heimdall.module.punishments;

import com.heimdall.core.command.CommandHandler;
import com.heimdall.core.command.CommandSource;
import com.heimdall.core.command.CommandSpec;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.http.model.ResolvedName;
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
import com.heimdall.core.punish.PunishmentParser;
import com.heimdall.core.text.Msg;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.tunnel.Capabilities;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Native punishments: local mirror, login/chat/command gates, /ban family, LiteBans hook.
 */
public final class HeimdallPunishmentsModule implements HeimdallModule {

    public static final String ID = "punishments";

    private volatile ModuleContext context;
    private volatile MirrorStore<ActivePunishment> mirror;
    private volatile String lastEtag;

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
        PunishmentSettings settings = PunishmentSettings.from(context.config());
        Path path = context.platform().dataDirectory().resolve("punishments-mirror.json");
        this.mirror = MirrorStore.builder(context.logger(), path, ActivePunishment.class)
                .policy(MirrorPolicy.builder()
                        .windowMs(TimeUnit.HOURS.toMillis(24))
                        .maxExtensionMs(TimeUnit.HOURS.toMillis(24))
                        .saveDebounceMs(500)
                        .build())
                .scheduler(context.executors().scheduler())
                .open();

        context.interceptLogin(this::onLogin, 50);
        ServerRole role = context.platform().role();
        boolean backend = role == ServerRole.ENFORCER || role == ServerRole.STANDALONE;
        if (backend) {
            context.interceptChat(this::onChat, 50);
            context.interceptCommand(this::onCommand, 50);
        }
        context.tunnel().subscribe("punish.apply", applyHandler());
        context.tunnel().subscribe("punish.revoke", revokeHandler());
        context.tunnel().subscribe("punish.import", importHandler());
        context.scheduleRepeating(new Runnable() {
            @Override
            public void run() {
                sync();
            }
        }, 5_000, TimeUnit.MINUTES.toMillis(5));
        registerCommands(context, settings, role);
        context.executors().io().execute(new Runnable() {
            @Override
            public void run() {
                sync();
            }
        });
        LiteBansSupport.tryHook(this, context);
    }

    @Override
    public void disable() {
        if (mirror != null) {
            try {
                mirror.close();
            } catch (Exception e) {
                context.logger().error("closing punishment mirror", e);
            }
            mirror = null;
        }
        if (INSTANCE == this) INSTANCE = null;
        context = null;
    }

    private void registerCommands(ModuleContext context, PunishmentSettings settings, ServerRole role) {
        boolean proxy = role == ServerRole.GATEKEEPER || role == ServerRole.STANDALONE;
        boolean backend = role == ServerRole.ENFORCER || role == ServerRole.STANDALONE;
        if (settings.rootAliases && proxy) {
            bind(context, "ban", "heimdall.punishments.ban", "ban");
            bind(context, "tempban", "heimdall.punishments.ban", "ban");
            bind(context, "ipban", "heimdall.punishments.ipban", "ipban");
            bind(context, "unban", "heimdall.punishments.unban", "unban");
            bind(context, "kick", "heimdall.punishments.kick", "kick");
            bind(context, "warn", "heimdall.punishments.warn", "warn");
            bind(context, "unwarn", "heimdall.punishments.warn", "unwarn");
            bind(context, "history", "heimdall.punishments.history", "history");
            bind(context, "staffhistory", "heimdall.punishments.history", "staffhistory");
            bind(context, "banlist", "heimdall.punishments.history", "banlist");
            bind(context, "dupeip", "heimdall.punishments.dupeip", "dupeip");
            bind(context, "iphistory", "heimdall.punishments.dupeip", "iphistory");
        }
        if (settings.rootAliases) {
            bind(context, "mute", "heimdall.punishments.mute", "mute");
            bind(context, "tempmute", "heimdall.punishments.mute", "mute");
            bind(context, "unmute", "heimdall.punishments.unban", "unmute");
        }
    }

    private void bind(ModuleContext context, final String name, String permission, final String type) {
        context.registerCommand(CommandSpec.named(name)
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
    }

    void onStaffCommand(CommandSource source, String type, List<String> args) {
        ModuleContext ctx = this.context;
        if (ctx == null) return;
        PunishmentSettings settings = PunishmentSettings.from(ctx.config());
        if (!settings.replaceMode() && !type.equals("history") && !type.equals("banlist")
                && !type.equals("dupeip") && !type.equals("iphistory") && !type.equals("staffhistory")) {
            source.sendMessage(Msg.legacy("§eNative punishments are not in replace mode. "
                    + "Use LiteBans or switch mode in the dashboard."));
            return;
        }
        if (type.equals("history") || type.equals("banlist") || type.equals("staffhistory")
                || type.equals("dupeip") || type.equals("iphistory")) {
            source.sendMessage(Msg.legacy("§7Look up punishments on the dashboard player page for now."));
            return;
        }
        if ("unban".equals(type) || "unmute".equals(type) || "unwarn".equals(type)) {
            if (args.isEmpty()) {
                source.sendMessage(Msg.legacy("§cUsage: /" + type + " <player>"));
                return;
            }
            revoke(source, type, args.get(0));
            return;
        }
        PunishmentParser.Parsed parsed;
        try {
            parsed = PunishmentParser.parse(args);
        } catch (IllegalArgumentException e) {
            source.sendMessage(Msg.legacy("§cUsage: /" + type + " <player> [duration] [reason]"));
            return;
        }
        boolean silent = parsed.silent || (settings.silentByDefault && !parsed.publicFlag);
        String durationType = type;
        if ("ban".equals(type) && parsed.durationMinutes != null) durationType = "ban";
        issue(source, durationType, parsed, silent);
    }

    private void issue(final CommandSource source, final String type, final PunishmentParser.Parsed parsed,
            final boolean silent) {
        final ModuleContext ctx = this.context;
        final PlayerHandle online = ctx.platform().players().byName(parsed.target).orElse(null);
        if (online != null) {
            submitIssue(source, type, online.uuid().toString(), online.name(), parsed, silent);
            return;
        }
        source.sendMessage(Msg.legacy("§eResolving §f" + parsed.target + "§e..."));
        ctx.api().resolveName(parsed.target).whenComplete((resolved, failure) -> {
            if (failure != null || resolved == null) {
                source.sendMessage(Msg.legacy("§cCould not resolve §f" + parsed.target));
                return;
            }
            submitIssue(source, type, resolved.uuid(), resolved.username(), parsed, silent);
        });
    }

    private void submitIssue(CommandSource source, String type, String uuid, String name,
            PunishmentParser.Parsed parsed, boolean silent) {
        ModuleContext ctx = this.context;
        ActivePunishment local = new ActivePunishment();
        local.id = "local-" + uuid + "-" + type;
        local.type = type;
        local.targetUuid = uuid;
        local.targetName = name;
        local.reason = parsed.reason;
        local.silent = silent;
        if (parsed.durationMinutes != null) {
            local.expiresAt = java.time.Instant.now()
                    .plusSeconds(parsed.durationMinutes.intValue() * 60L).toString();
        }
        if (mirror != null && !"kick".equals(type) && !"warn".equals(type)) {
            mirror.record(keyFor(local), local);
        }
        kickIfBanned(uuid, local);
        ctx.api().issuePunishment(
                "ipban".equals(type) ? "ipban" : type,
                uuid,
                name,
                parsed.reason == null ? "" : parsed.reason,
                parsed.durationMinutes,
                silent,
                source.uuid() == null ? null : source.uuid().toString(),
                source.name()).whenComplete((result, failure) -> {
            if (failure != null) {
                source.sendMessage(Msg.legacy("§cPunishment queued locally but the bot refused: "
                        + failure.getMessage()));
                return;
            }
            source.sendMessage(Msg.legacy("§a" + type + " issued for §f" + name));
        });
    }

    private void revoke(CommandSource source, String type, String target) {
        ModuleContext ctx = this.context;
        PlayerHandle online = ctx.platform().players().byName(target).orElse(null);
        final String name = online == null ? target : online.name();
        Runnable send = new Runnable() {
            @Override
            public void run() {
                source.sendMessage(Msg.legacy("§eRevoke is applied from the dashboard for now, "
                        + "or re-issue after looking up the id. Target: §f" + name));
            }
        };
        ctx.executors().io().execute(send);
    }

    private Verdict onLogin(LoginAttempt attempt) {
        if (mirror == null) return Verdict.abstain();
        PunishmentSettings settings = PunishmentSettings.from(context.config());
        if (!settings.replaceMode()) return Verdict.abstain();
        ActivePunishment ban = mirror.get("ban:" + attempt.uuid().toString());
        if (ban != null && !ban.expired(System.currentTimeMillis())) {
            return Verdict.deny(Msg.legacy(render(settings.banScreen, ban)));
        }
        String ip = attempt.ipAddress();
        if (ip != null && !settings.ipSalt.isEmpty()) {
            String digest = hmacIp(ip, settings.ipSalt);
            ActivePunishment ipban = mirror.get("ipban:" + digest);
            if (ipban != null && !ipban.expired(System.currentTimeMillis())) {
                boolean checkIp = context.platform().role() != ServerRole.ENFORCER
                        || Boolean.TRUE.equals(null);
                if (context.platform().role() != ServerRole.ENFORCER) {
                    return Verdict.deny(Msg.legacy(render(settings.banScreen, ipban)));
                }
            }
        }
        return Verdict.abstain();
    }

    private Verdict onChat(ChatMessage message) {
        if (mirror == null) return Verdict.abstain();
        PunishmentSettings settings = PunishmentSettings.from(context.config());
        if (!settings.replaceMode()) return Verdict.abstain();
        ActivePunishment mute = mirror.get("mute:" + message.senderUuid());
        if (mute != null && !mute.expired(System.currentTimeMillis())) {
            return Verdict.deny(Msg.legacy(render(settings.muteScreen, mute)));
        }
        return Verdict.abstain();
    }

    private Verdict onCommand(CommandAttempt attempt) {
        if (mirror == null) return Verdict.abstain();
        PunishmentSettings settings = PunishmentSettings.from(context.config());
        if (!settings.replaceMode()) return Verdict.abstain();
        ActivePunishment mute = mirror.get("mute:" + attempt.senderUuid());
        if (mute == null || mute.expired(System.currentTimeMillis())) return Verdict.abstain();
        if (settings.blockedCommands.contains(attempt.label())) {
            return Verdict.deny(Msg.legacy(render(settings.muteScreen, mute)));
        }
        return Verdict.abstain();
    }

    private TunnelMessageHandler applyHandler() {
        return new TunnelMessageHandler() {
            @Override
            public void onMessage(Envelope envelope) {
                Payload payload = envelope.payload();
                ActivePunishment p = fromPayload(payload);
                if (p == null || mirror == null) return;
                if ("kick".equals(p.type) || "warn".equals(p.type)) {
                    kickIfBanned(p.targetUuid, p);
                    return;
                }
                mirror.record(keyFor(p), p);
                kickIfBanned(p.targetUuid, p);
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

    private void sync() {
        ModuleContext ctx = this.context;
        MirrorStore<ActivePunishment> store = this.mirror;
        if (ctx == null || store == null || !ctx.api().isUsable()) return;
        ctx.api().punishmentSync(lastEtag).whenComplete((data, failure) -> {
            if (failure != null || data == null) return;
            try {
                Payload payload = data;
                java.util.Map<String, ActivePunishment> authoritative = new java.util.LinkedHashMap<String, ActivePunishment>();
                for (Payload row : payload.children("punishments")) {
                    ActivePunishment p = fromPayload(row);
                    if (p != null) authoritative.put(keyFor(p), p);
                }
                store.reconcile(authoritative);
            } catch (RuntimeException e) {
                ctx.logger().error("punishment sync apply failed", e);
            }
        });
    }

    private void kickIfBanned(String uuid, ActivePunishment punishment) {
        if (uuid == null || context == null) return;
        if (!"ban".equals(punishment.type) && !"ipban".equals(punishment.type)
                && !"kick".equals(punishment.type)) {
            return;
        }
        ServerRole role = context.platform().role();
        if (role == ServerRole.ENFORCER && !"kick".equals(punishment.type)) {
            return;
        }
        PlayerHandle player = context.platform().players().byUuid(parseUuid(uuid)).orElse(null);
        if (player == null) return;
        PunishmentSettings settings = PunishmentSettings.from(context.config());
        String screen = "kick".equals(punishment.type) ? settings.kickScreen : settings.banScreen;
        player.kick(Msg.legacy(render(screen, punishment)));
    }

    static String keyFor(ActivePunishment p) {
        if ("ipban".equals(p.type) && p.ipDigest != null) return "ipban:" + p.ipDigest;
        return p.type + ":" + p.targetUuid;
    }

    static ActivePunishment fromPayload(Payload payload) {
        ActivePunishment p = new ActivePunishment();
        p.id = payload.string("id", "");
        p.type = payload.string("type", "");
        p.targetUuid = payload.string("targetUuid", null);
        p.targetName = payload.string("targetName", null);
        p.ipDigest = payload.string("ipDigest", null);
        p.reason = payload.string("reason", "");
        p.expiresAt = payload.string("expiresAt", null);
        p.silent = payload.bool("silent", false);
        if (p.type.isEmpty()) return null;
        return p;
    }

    private static String render(String template, ActivePunishment p) {
        String reason = p.reason == null ? "" : p.reason;
        return template.replace("{reason}", reason)
                .replace("{player}", p.targetName == null ? "" : p.targetName);
    }

    static String hmacIp(String ip, String salt) {
        try {
            String canonical = ip.trim().toLowerCase(Locale.ROOT);
            if (canonical.startsWith("::ffff:")) canonical = canonical.substring(7);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : raw) hex.append(String.format("%02x", Integer.valueOf(b & 0xff)));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
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
}
