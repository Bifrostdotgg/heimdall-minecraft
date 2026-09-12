package com.heimdall.module.punishments;

import com.heimdall.core.admin.PunishmentAdmin;
import com.heimdall.core.command.CommandCompleter;
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
import com.heimdall.core.punish.PunishmentAnnouncement;
import com.heimdall.core.punish.PunishmentIp;
import com.heimdall.core.punish.PunishmentParser;
import com.heimdall.core.punish.PunishmentView;
import com.heimdall.core.punish.SilenceDecision;
import com.heimdall.core.remoteconfig.ModuleConfig;
import com.heimdall.core.remoteconfig.ModuleConfigListener;
import com.heimdall.core.session.PlayerSessionListener;
import com.heimdall.core.text.Msg;
import com.heimdall.core.text.Template;
import com.heimdall.core.tunnel.Capabilities;
import com.heimdall.core.tunnel.TunnelBus;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import com.heimdall.core.util.Registration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Iterator;
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
 *
 * <p>Chat announcements belong to the outermost instance a player is connected through: a
 * gatekeeper or standalone server announces, an enforcer backend never does. See
 * {@link #announcesHere(ServerRole)}.
 */
public final class HeimdallPunishmentsModule implements HeimdallModule, PunishmentAdmin {

    public static final String ID = "punishments";

    /** The bot asking which other accounts have shared an address with a player. */
    static final String DUPEIP_QUERY = "dupeip.query";

    /** The reply to {@link #DUPEIP_QUERY}, correlated against the request's envelope id. */
    static final String DUPEIP_RESULT = "dupeip_result";

    /**
     * The most alts either caller will show, and the point past which {@code truncated} is set.
     *
     * <p>Fifty is a display bound, not a safety one - the safety is that a bound exists at all. See
     * {@link LastIpStore#altsOf} for why an unbounded answer here can be a guild's entire player
     * history: nothing prunes the last-address file, and on a gatekeeper whose backends do not
     * forward addresses every row shares one. Nobody reads the fiftieth name on a chat line or a
     * dashboard card, so the cut costs nothing a real operator wanted.
     */
    static final int DUPEIP_ALT_LIMIT = 50;

    /** How many recently-issued operation ids are remembered for echo suppression. */
    static final int ECHO_MEMORY = 256;

    /**
     * The durations tab completion offers once a target is present.
     *
     * <p>Seven entries, not the whole grammar. The parser takes anything from {@code 45s} to
     * {@code 2mo3d}; a completion list is for the lengths people actually pick, and one that tried
     * to be exhaustive would be a worse menu than typing.
     */
    static final List<String> DURATION_SUGGESTIONS = Collections.unmodifiableList(
            Arrays.asList("30m", "1h", "6h", "1d", "7d", "30d", "perm"));

    /** The two silence options, completed only for a sender allowed to use them. */
    private static final List<String> SILENCE_FLAGS = Collections.unmodifiableList(
            Arrays.asList("-s", "-p"));

    private volatile ModuleContext context;
    private volatile MirrorStore<ActivePunishment> mirror;
    private volatile PunishmentOutbox outbox;
    private volatile LastIpStore lastIps;
    private volatile GeoCountryLookup geo;
    private volatile long lastFullSyncAt;
    /**
     * Operation ids this server issued itself, newest last, capped at {@value #ECHO_MEMORY}.
     *
     * <p>The bot excludes the issuing server from its {@code punish.apply} fanout, but it can only
     * do that when it knows which server issued the punishment - and on the paths where the origin
     * cannot be resolved it sends the frame to everybody, including us. We have already applied it
     * and already announced it, so the echo is one redundant apply and, without this, a second
     * chat line saying the same player was banned twice.
     *
     * <p>Only the announcement is skipped. The mirror write is idempotent and still happens,
     * because the echo may carry the server-side id and expiry that the local row was invented
     * without.
     *
     * <p>A bounded, ordered set rather than a cache with expiry: the echo arrives within a round
     * trip of the issue, so a few hundred entries is minutes of the busiest moderation any server
     * has, and nothing here is worth a scheduled sweep. Overflow drops the oldest, which is the
     * one whose echo has certainly already been and gone.
     */
    private final Set<String> locallyIssued = Collections.synchronizedSet(
            new LinkedHashSet<String>());

    /**
     * Every player name this instance can complete, kept current rather than looked up.
     *
     * <p>Populated from three places, each of which knows something the others do not: the online
     * roster (who is here), the last-address file (everyone this gatekeeper or standalone server
     * has ever seen, and an enforcer backend has none), and the punishment mirror (everyone with
     * an active punishment, wherever they were punished from). See {@link KnownNames} for why it
     * is an index rather than a question asked at completion time.
     */
    private final KnownNames names = new KnownNames();

    private final AtomicBoolean flushing = new AtomicBoolean();
    private final AtomicBoolean flushAgain = new AtomicBoolean();
    private final List<Registration> aliasBinds = new CopyOnWriteArrayList<Registration>();

    /**
     * The enabled instance, or {@code null}.
     *
     * <p><strong>Public because two callers can only reach this module reflectively, and
     * {@code Field#get} needs a public field on a public class.</strong> Those two are
     * {@code BukkitPunishmentGuard} (in {@code :platform-bukkit}, which must not compile against a
     * feature module) and {@code OffendCommand}'s {@code nativeReplaceActive} (in
     * {@code :module-offenses}, which must not depend on a sibling module). Both were silently
     * dead while this field was package-private: {@code getField} sees only public members, so the
     * freeze and muted-sign guards never fired and {@code /offend} always reported the dispatch
     * path. Narrowing it again re-breaks them without a compiler error, which is why the reason is
     * written here rather than left to a reviewer to reconstruct.
     *
     * <p>The admin command tree does <em>not</em> use this. It goes through {@link PunishmentAdmin},
     * which the compiler checks - see that interface for what the reflective version cost.
     */
    public static volatile HeimdallPunishmentsModule INSTANCE;

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
                names.joined(player.name());
                notifyWarn(player);
            }
        });
        context.onPlayerQuit(new PlayerSessionListener() {
            @Override
            public void onPlayerSession(PlayerHandle player, long timestampMs) {
                names.quit(player.name());
            }
        });
        seedKnownNames();
        context.tunnel().subscribe("punish.apply", applyHandler());
        context.tunnel().subscribe("punish.revoke", revokeHandler());
        context.tunnel().subscribe("punish.import", importHandler());
        context.tunnel().subscribe(DUPEIP_QUERY, dupeipHandler(context.tunnel(), context.logger()));
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
                // The same completer the /hd tree gets, so /ban and /hd ban cannot suggest
                // different things. The tree filters a subcommand's answer by prefix on its way
                // out; here there is nothing between the module and the platform, which is why
                // complete() filters its own answer rather than leaving that to a caller.
                .completer(new CommandCompleter() {
                    @Override
                    public List<String> complete(CommandSource source, List<String> args) {
                        return HeimdallPunishmentsModule.this.complete(source, type, args);
                    }
                })
                .build());
        aliasBinds.add(handle);
    }

    /**
     * Fills the completion index from everything already on disk or in memory at enable time.
     *
     * <p>Without this a freshly started server completes nothing until somebody joins, which is
     * precisely when a moderator wants to ban the player who just left.
     *
     * <p>The roster read is wrapped because {@code onlinePlayers()} is allowed to throw rather
     * than claim the server is empty, and enable runs on whatever thread a config push landed on.
     * An index that starts one join behind is a worse tab completion for a minute; an exception
     * here is a module that failed to enable.
     */
    private void seedKnownNames() {
        ModuleContext ctx = this.context;
        if (ctx == null) return;
        LastIpStore ips = this.lastIps;
        if (ips != null) {
            names.rememberAll(ips.allNames());
        }
        rememberMirrorNames();
        try {
            for (PlayerHandle player : ctx.platform().players().onlinePlayers()) {
                names.joined(player.name());
            }
        } catch (RuntimeException raced) {
            ctx.logger().debug(() -> "could not read the online list to seed name completion: "
                    + raced);
        }
    }

    /**
     * Adds every punished player the mirror knows about, and rebuilds the revoke-candidacy index.
     *
     * <p>Called after a sync reconciles, whose answer is authoritative for the whole mirror - so
     * the index is rebuilt from scratch and swapped in rather than patched row by row. This is the
     * one place that walks every key, and it runs on a sync rather than on a keystroke.
     */
    private void rememberMirrorNames() {
        MirrorStore<ActivePunishment> store = this.mirror;
        if (store == null) return;
        KnownNames.Index rebuilt = KnownNames.index();
        for (String key : store.keys()) {
            ActivePunishment p = store.get(key);
            if (p == null) continue;
            names.remember(p.targetName);
            String family = revokeFamily(p.type);
            if (family == null || p.targetName == null) continue;
            Long ends = p.expiresAtMillis();
            if (ends != null && ends.longValue() <= System.currentTimeMillis()) continue;
            rebuilt.add(family, p.targetName, ends == null ? KnownNames.NEVER : ends.longValue());
        }
        names.install(rebuilt);
    }

    /** Whether this module is enabled right now - {@link PunishmentAdmin}'s half of the contract. */
    @Override
    public boolean isAvailable() {
        return context != null;
    }

    /**
     * Tab completion for every punishment verb, on {@code /hd}, on {@code /hdp} and on the root
     * aliases alike.
     *
     * <h2>Position is counted in non-flag tokens</h2>
     *
     * <p>{@code -s} and {@code -p} are accepted anywhere, so "the target" cannot be "the first
     * argument" - {@code /ban -s Ste} with a tab after it is still completing a name. The flags
     * are dropped and the remaining words counted, which is the same rule
     * {@link com.heimdall.core.punish.PunishmentParser} applies when the command actually runs. A
     * completer that disagreed with the parser about which word is the target would suggest names
     * into the reason.
     *
     * <h2>What is offered where</h2>
     *
     * <ul>
     *   <li>A word starting with {@code -} completes the flags, and only for a sender who may use
     *       them. Offering {@code -s} to somebody the command will then refuse is a worse answer
     *       than offering nothing.
     *   <li>The target position completes names. For {@code unban}, {@code unmute} and
     *       {@code unwarn} only players with an active punishment of that family, because a name
     *       with nothing to lift is never the answer.
     *   <li>After the target, while no duration has been typed and the verb takes one, a short
     *       duration vocabulary.
     *   <li>Inside the reason, nothing. There is no vocabulary for free text, and suggesting
     *       player names there is how a name ends up in the reason of a ban on somebody else.
     * </ul>
     */
    @Override
    public List<String> complete(CommandSource source, String verb, List<String> args) {
        if (context == null || args == null) {
            return Collections.emptyList();
        }
        // An empty list is the target position with nothing typed yet, not "no completion". Bukkit
        // supplies a trailing "" for the word being typed and the /hd tree does too, but that is a
        // convention of two callers rather than a guarantee of the interface, and a completer that
        // returned nothing for an empty list offered nothing at all on whichever one stopped.
        String partial = args.isEmpty() ? "" : args.get(args.size() - 1);
        if (partial == null) partial = "";
        if (partial.startsWith("-")) {
            return flagSuggestions(source, partial);
        }
        if ("banlist".equals(verb)) {
            return Collections.emptyList();
        }
        int typedBefore = 0;
        boolean durationTyped = false;
        for (int i = 0; i < args.size() - 1; i++) {
            String token = args.get(i) == null ? "" : args.get(i).trim();
            if (token.isEmpty() || isFlag(token)) continue;
            typedBefore++;
            if (typedBefore > 1 && PunishmentParser.looksLikeDuration(token)) {
                durationTyped = true;
            }
        }
        if (typedBefore == 0) {
            return names.matching(partial, COMPLETION_LIMIT, targetFilter(verb));
        }
        if (durationTyped || !takesDuration(verb)) {
            return Collections.emptyList();
        }
        return prefixed(DURATION_SUGGESTIONS, partial);
    }

    /** Whether a token is one of the options rather than a word of the command. */
    private static boolean isFlag(String token) {
        return "-s".equalsIgnoreCase(token)
                || "-p".equalsIgnoreCase(token)
                || token.toLowerCase(Locale.ROOT).startsWith("--sender=");
    }

    private static List<String> flagSuggestions(CommandSource source, String partial) {
        if (!mayOverrideSilence(source)) {
            return Collections.emptyList();
        }
        return prefixed(SILENCE_FLAGS, partial);
    }

    /** Verbs that read a duration out of their arguments. A revoke and a lookup do not. */
    private static boolean takesDuration(String verb) {
        return "ban".equals(verb) || "tempban".equals(verb) || "ipban".equals(verb)
                || "mute".equals(verb) || "tempmute".equals(verb) || "warn".equals(verb);
    }

    /**
     * The names a revoke verb may complete, or {@code null} when every known name is fair game.
     *
     * <p>Lower-cased, because {@link KnownNames} matches that way and a moderator typing
     * {@code steve} means Steve.
     *
     * <p><strong>A lookup, not a scan.</strong> This used to walk every key in the punishment
     * mirror and do a {@code get} per key - on every tab press, for every revoke verb, on the main
     * server thread on the Bukkit family. The set is now maintained in {@link KnownNames} at the
     * moments a punishment lands or is lifted, which are the same moments the names themselves are
     * recorded.
     */
    private Set<String> targetFilter(String verb) {
        String family = revokeFamily(verb);
        if (family == null || !verb.startsWith("un")) {
            // Only the three revoke verbs filter. An issue verb takes anybody, and rollback takes
            // whoever has anything at all, which is what "every known name" already means.
            return null;
        }
        return names.withActive(family, System.currentTimeMillis());
    }

    /**
     * The revoke family a punishment type or a revoke verb belongs to, or {@code null}.
     *
     * <p>Both spellings in one table, because the two sides of this question are asked in
     * different vocabularies: a moderator types {@code unban}, while an apply frame and a mirror
     * row name the type ({@code ban}, {@code ipban}). A {@code kick}, a {@code geo} or a
     * {@code subnet} has no revoke verb that names a player and belongs to no family.
     */
    static String revokeFamily(String typeOrVerb) {
        if ("ban".equals(typeOrVerb) || "ipban".equals(typeOrVerb) || "unban".equals(typeOrVerb)) {
            return "ban";
        }
        if ("mute".equals(typeOrVerb) || "unmute".equals(typeOrVerb)) {
            return "mute";
        }
        if ("warn".equals(typeOrVerb) || "unwarn".equals(typeOrVerb)) {
            return "warn";
        }
        return null;
    }

    /** The mirror types one family covers. */
    private static String[] familyTypes(String family) {
        if ("ban".equals(family)) return new String[] {"ban", "ipban"};
        if ("mute".equals(family)) return new String[] {"mute"};
        if ("warn".equals(family)) return new String[] {"warn"};
        return new String[0];
    }

    /**
     * Recomputes one player's candidacy for one revoke family, from the mirror.
     *
     * <p>Called wherever a punishment lands or is lifted, which is a handful of times per
     * punishment rather than once per keystroke - so this side may read the mirror, and the
     * completion side may not. Recomputed rather than incremented and decremented because a family
     * can hold two rows at once: {@code /unban} lifts a ban and an ipban together, and a player
     * with one of each still has something to lift after the first goes.
     */
    private void refreshRevokeCandidacy(String family, String uuid, String name) {
        if (family == null || uuid == null || name == null || name.isEmpty()) return;
        List<ActivePunishment> live = activeOf(uuid, familyTypes(family));
        long latest = 0L;
        for (int i = 0; i < live.size(); i++) {
            Long ends = live.get(i).expiresAtMillis();
            if (ends == null) {
                latest = KnownNames.NEVER;
                break;
            }
            if (ends.longValue() > latest) {
                latest = ends.longValue();
            }
        }
        if (latest <= 0L) {
            names.unpunished(family, name);
        } else {
            names.punished(family, name, latest);
        }
    }

    private static List<String> prefixed(List<String> candidates, String partial) {
        String needle = partial.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            if (candidate.toLowerCase(Locale.ROOT).startsWith(needle)) {
                out.add(candidate);
            }
        }
        return out;
    }

    @Override
    public void onStaffCommand(CommandSource source, String type, List<String> args) {
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
            // flags() rather than parse(): a revoke takes no duration, and parse would read the
            // "3d" in "/unban Steve 3d ban evasion" as one and drop it out of the reason.
            PunishmentParser.Flags options = PunishmentParser.flags(args);
            if (options.rest.isEmpty()) {
                source.sendMessage(Msg.legacy("§cUsage: /" + type + " <player> [reason]"));
                return;
            }
            // The flags travel rather than being resolved here: a revoke's default is the
            // silence of the row it lifts, and which row that is is not known until the target
            // has been resolved and matched. See submitRevoke.
            String reason = options.rest.size() > 1 ? join(options.rest, 1) : "";
            revoke(source, type, options.rest.get(0), reason, options.silent, options.publicFlag);
            return;
        }
        PunishmentParser.Parsed parsed;
        try {
            parsed = PunishmentParser.parse(args);
        } catch (IllegalArgumentException e) {
            source.sendMessage(Msg.legacy("§cUsage: /" + type + " <player> [duration] [reason]"));
            return;
        }
        if (("tempban".equals(type) || "tempmute".equals(type)) && parsed.durationSeconds == null) {
            source.sendMessage(Msg.legacy("§cA duration is required for /" + type + "."));
            return;
        }
        if (parsed.durationSeconds != null
                && parsed.durationSeconds.longValue() > PunishmentParser.MAX_ISSUE_SECONDS) {
            // Refused here rather than clamped, and refused before anything is written or sent:
            // the same ceiling the slash command and the dashboard form apply, so a moderator
            // cannot get a length from one surface that another would have rejected.
            source.sendMessage(Msg.legacy("§cThe longest punishment that can be issued is "
                    + PunishmentParser.MAX_ISSUE_YEARS + " years. Use §fperm§c for one that "
                    + "never ends."));
            return;
        }
        String issueType = type;
        if ("tempban".equals(type)) issueType = "ban";
        if ("tempmute".equals(type)) issueType = "mute";
        SilenceDecision decision = silence(source, parsed.silent, parsed.publicFlag, settings);
        if (decision.refused()) {
            source.sendMessage(Msg.legacy("§c" + SilenceDecision.REFUSAL_MESSAGE));
            return;
        }
        issue(source, issueType, parsed, decision.silent());
    }

    /**
     * Resolves {@code -s}/{@code -p} against the guild default and the sender's permission.
     *
     * <p>The permission is read here rather than inside {@link SilenceDecision} so the decision
     * stays a pure function of four booleans, which is what makes its table of cases testable
     * without a server.
     */
    private static SilenceDecision silence(CommandSource source, boolean silentFlag,
            boolean publicFlag, PunishmentSettings settings) {
        return SilenceDecision.decide(silentFlag, publicFlag, settings.silentByDefault,
                mayOverrideSilence(source));
    }

    /**
     * Whether this sender may depart from whatever the silence default is.
     *
     * <p>One place, so the issue path and the revoke path cannot answer differently, and so the
     * admin implication is not written twice and then corrected once.
     */
    private static boolean mayOverrideSilence(CommandSource source) {
        return SilenceDecision.mayOverride(
                source.hasPermission(SilenceDecision.OVERRIDE_PERMISSION),
                source.hasPermission(SilenceDecision.ADMIN_PERMISSION));
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

    /**
     * Files an issued punishment, applies it locally and announces it.
     *
     * <p>Package-private rather than private so a test can call it with the module already
     * disabled, which is the one condition the guard below exists for and the one an end-to-end
     * test cannot stage: the NPE it prevents happens inside a {@code whenComplete} callback, where
     * nothing observes it.
     */
    void submitIssue(CommandSource source, String type, String uuid, String name,
            PunishmentParser.Parsed parsed, boolean silent) {
        ModuleContext ctx = this.context;
        // Reached from inside the resolveName future as well as synchronously, so the module can
        // be disabled between the command and this. Every other context read in this file guards;
        // these two did not, and a config push that turned the module off mid-resolve threw inside
        // a CompletableFuture, where the exception is swallowed and the moderator is told nothing.
        if (ctx == null) return;
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
        // Before anything is sent, so an echo cannot outrun the record of what caused it.
        rememberIssued(opId);
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
        if (parsed.durationSeconds != null) {
            local.durationSeconds = parsed.durationSeconds;
            local.expiresAt = Instant.ofEpochMilli(now)
                    .plusSeconds(parsed.durationSeconds.longValue()).toString();
        }
        names.remember(name);
        String mirrorKey = keyFor(local);
        if (mirror != null && !"kick".equals(type) && mirrorKey != null) {
            mirror.record(mirrorKey, local);
            // After the record, because the candidacy is recomputed from the mirror.
            refreshRevokeCandidacy(revokeFamily(type), uuid, name);
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
        if (parsed.durationSeconds != null) {
            putDuration(body, parsed.durationSeconds.longValue());
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
        announce(PunishmentAnnouncement.issued(
                settings.announceIssue, viewOf(local, settings), now));
        flushSoon();
    }

    private void revoke(final CommandSource source, final String type, final String target,
            final String reason, final boolean silentFlag, final boolean publicFlag) {
        ModuleContext ctx = this.context;
        PlayerHandle online = ctx.platform().players().byName(target).orElse(null);
        if (online != null) {
            submitRevoke(source, type, online.uuid().toString(), online.name(), reason,
                    silentFlag, publicFlag);
            return;
        }
        LastIpStore.PlayerIps seen = lastIps == null ? null : lastIps.byName(target);
        if (seen != null) {
            submitRevoke(source, type, seen.uuid, seen.name, reason, silentFlag, publicFlag);
            return;
        }
        source.sendMessage(Msg.legacy("§eResolving §f" + target + "§e..."));
        ctx.api().resolveName(target).whenComplete((resolved, failure) -> {
            if (failure != null || resolved == null) {
                ActivePunishment local = findByName(target, revokeTypes(type));
                if (local != null) {
                    submitRevoke(source, type, local.targetUuid, local.targetName, reason,
                            silentFlag, publicFlag);
                    return;
                }
                source.sendMessage(Msg.legacy("§cCould not resolve §f" + target));
                return;
            }
            submitRevoke(source, type, resolved.uuid(), resolved.username(), reason,
                    silentFlag, publicFlag);
        });
    }

    /**
     * Files the revoke and announces it.
     *
     * <p><strong>A revoke's silence defaults to the silence of the punishment it lifts, not to the
     * guild's setting.</strong> Announcing "Adam unbanned Steve" on a server that was never told
     * Steve was banned discloses the very thing the silent ban was hiding, and it discloses it
     * later, out of context, to everybody. The tunnel path has always read the flag off the row;
     * this is the same rule for a moderator typing the command.
     *
     * <p>{@code -s} and {@code -p} still override it, and still need
     * {@link SilenceDecision#OVERRIDE_PERMISSION} to do so - against this default rather than
     * against the guild's, so lifting a silent ban loudly is the privileged act it should be.
     *
     * <p>The first match decides when a verb lifts several rows at once ({@code /unban} takes a
     * ban and an ipban together). They are one player's punishments and the announcement is one
     * line, so there is one flag to read and the primary row is the one to read it from.
     *
     * <p>Package-private for the same reason as {@link #submitIssue}: the disabled-mid-request
     * case is only reachable from a test that calls it directly.
     */
    void submitRevoke(CommandSource source, String type, String uuid, String name,
            String reason, boolean silentFlag, boolean publicFlag) {
        ModuleContext ctx = this.context;
        // See submitIssue. Reached from inside the resolveName future, so the module can be gone
        // by the time this runs, and the settings read at the end of it would NPE inside a
        // CompletableFuture where nobody sees it.
        if (ctx == null) return;
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
        SilenceDecision decision = SilenceDecision.decide(silentFlag, publicFlag,
                matches.get(0).silent, mayOverrideSilence(source));
        if (decision.refused()) {
            source.sendMessage(Msg.legacy("§c" + SilenceDecision.REFUSAL_MESSAGE));
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
        // After every eviction rather than inside the loop: a family can hold two rows, and
        // recomputing while the second is still in the mirror would put the name straight back.
        for (int i = 0; i < matches.size(); i++) {
            refreshRevokeCandidacy(revokeFamily(matches.get(i).type), uuid, name);
        }
        source.sendMessage(Msg.legacy("§aRevoked " + matches.size() + " punishment(s) for §f" + name));
        // Once, on the verb the moderator typed, rather than once per matching row: /unban lifts a
        // ban and an ipban together, and "Adam unbanned Steve" twice is noise, not information.
        PunishmentSettings settings = PunishmentSettings.from(ctx.config());
        announce(PunishmentAnnouncement.revoked(settings.announceRevoke, type,
                revokeView(matches.get(0), name, source.name(), reason, decision.silent(), settings),
                now));
        flushSoon();
    }

    /**
     * Writes the length of an outgoing punishment, in both units.
     *
     * <p>Seconds are the real field: a 30 second mute is a thing moderators ask for, and the old
     * minute-granular key rounded it up to a minute.
     *
     * <p><strong>{@code durationMinutes} is kept for exactly one release, then deleted.</strong>
     * A bot deployed before the seconds field existed reads only the minutes key, and a payload
     * carrying neither reads to it as no length at all - which turns every temporary ban and mute
     * issued from a server that updated first into a permanent one. The two halves of a deploy
     * are never simultaneous, so the plugin sends both until the bot side is out everywhere.
     * Remove this method and both call sites in the release after that.
     *
     * <p>Rounded up rather than down, for the same reason: an older bot reading a floored
     * {@code 0} would apply no length at all, and a 30 second mute arriving there as a minute is
     * the behaviour that surface already had.
     */
    static void putDuration(Payload.Builder body, long seconds) {
        body.put("durationSeconds", seconds);
        body.put("durationMinutes", (seconds + 59L) / 60L);
    }

    private static String[] revokeTypes(String type) {
        String family = revokeFamily(type);
        // Anything else is rollback, which lifts whatever is newest of any kind.
        return family == null ? new String[] {"ban", "ipban", "mute", "warn"} : familyTypes(family);
    }

    private static String typeLabel(String type) {
        if ("unban".equals(type)) return "ban";
        if ("unmute".equals(type)) return "mute";
        if ("unwarn".equals(type)) return "warn";
        if ("rollback".equals(type)) return "punishment";
        return type;
    }

    private Verdict onLogin(LoginAttempt attempt) {
        // Before the gate rather than after it: a player who is about to be denied is exactly the
        // one a moderator is about to type the name of, and a login is the first moment a proxy
        // learns a name at all.
        names.remember(attempt.username());
        if (lastIps != null && attempt.ipAddress() != null && !attempt.ipAddress().isEmpty()) {
            lastIps.record(attempt.uuid().toString(), attempt.username(), attempt.ipAddress(),
                    System.currentTimeMillis());
        }
        if (mirror == null) return Verdict.abstain();
        PunishmentSettings settings = PunishmentSettings.from(context.config());
        if (!settings.replaceMode()) return Verdict.abstain();
        ActivePunishment ban = mirror.get("ban:" + attempt.uuid().toString());
        if (ban != null && !ban.expired(System.currentTimeMillis())) {
            return Verdict.deny(render(ban, settings));
        }
        if (shouldCheckIpBan() && attempt.ipAddress() != null && !attempt.ipAddress().isEmpty()
                && !settings.ipSalt.isEmpty()) {
            String digest = PunishmentIp.hash(attempt.ipAddress(), settings.ipSalt);
            ActivePunishment ipban = mirror.get("ipban:" + digest);
            if (ipban != null && !ipban.expired(System.currentTimeMillis())) {
                return Verdict.deny(render(ipban, settings));
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
            return Verdict.deny(render(mute, settings));
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
            return Verdict.deny(render(mute, settings));
        }
        return Verdict.abstain();
    }

    private void notifyWarn(PlayerHandle player) {
        if (player == null || mirror == null || context == null) return;
        PunishmentSettings settings = PunishmentSettings.from(context.config());
        if (!settings.replaceMode()) return;
        ActivePunishment warn = mirror.get("warn:" + player.uuid());
        if (warn == null || warn.expired(System.currentTimeMillis())) return;
        player.sendMessage(render(warn, settings));
    }

    private TunnelMessageHandler applyHandler() {
        return new TunnelMessageHandler() {
            @Override
            public void onMessage(Envelope envelope) {
                Payload payload = envelope.payload();
                ActivePunishment p = fromPayload(payload);
                if (p == null || mirror == null) return;
                boolean echo = isOwnEcho(payload.string("opId", ""));
                PunishmentSettings settings = PunishmentSettings.from(context.config());
                if ("kick".equals(p.type)) {
                    applyLive(p.targetUuid, p, settings);
                    if (!echo) announce(announcementFor(p, settings));
                    return;
                }
                String key = keyFor(p);
                if (key == null) return;
                names.remember(p.targetName);
                mirror.record(key, p);
                refreshRevokeCandidacy(revokeFamily(p.type), p.targetUuid, p.targetName);
                applyLive(p.targetUuid, p, settings);
                if (!echo) announce(announcementFor(p, settings));
            }
        };
    }

    /**
     * Whether a {@code punish.revoke} frame describes something a server should be told about.
     *
     * <p>A revoke frame carries {@code revokeCause}, and only {@code manual} is a moderator
     * deciding to lift a punishment. The other two are bookkeeping, and announcing them would be
     * worse than noise:
     *
     * <ul>
     *   <li>{@code expiry} - a temporary ban reached its end. Every tempban would produce a second
     *       chat line hours or days later, attributed to nobody, telling a server something it can
     *       already see. Nothing decided anything.
     *   <li>{@code override} - the punishment was replaced by a newer one for the same player. The
     *       apply frame for the replacement is the event, and it announces itself; a paired
     *       "unbanned" beside it reads as the moderator having undone their own ban.
     * </ul>
     *
     * <p>An absent cause is treated as {@code manual}. That is the only safe reading: it is what
     * an older bot sends, and every revoke an older bot sends is a lifting, since expiry and
     * override are what the field was added to distinguish. Unknown causes are announced too, for
     * the same reason - a new cause the plugin has never heard of is more likely to be a lifting
     * with a name than a sweep, and a plugin that stayed quiet for anything it did not recognise
     * would silently stop announcing the day the bot renamed the value.
     *
     * <p>The bot sends an empty {@code revokedBy} for non-manual causes, so an announcement that
     * slipped through would be attributed to "Console", which is a second reason not to make one.
     */
    static boolean announceableRevoke(String revokeCause) {
        if (revokeCause == null) return true;
        String cause = revokeCause.trim().toLowerCase(Locale.ROOT);
        return !"expiry".equals(cause) && !"override".equals(cause);
    }

    /** Records an operation id as ours, dropping the oldest once {@link #ECHO_MEMORY} is full. */
    private void rememberIssued(String opId) {
        if (opId == null || opId.isEmpty()) return;
        synchronized (locallyIssued) {
            locallyIssued.remove(opId);
            locallyIssued.add(opId);
            while (locallyIssued.size() > ECHO_MEMORY) {
                Iterator<String> oldest = locallyIssued.iterator();
                oldest.next();
                oldest.remove();
            }
        }
    }

    /**
     * Whether an incoming apply frame is this server's own punishment coming back.
     *
     * <p>Consumed rather than merely read: an operation id is used once, and forgetting it here
     * keeps the memory to punishments whose echo has not arrived yet. A frame with no
     * {@code opId} is somebody else's by definition, since every id we put in this set we minted.
     */
    boolean isOwnEcho(String opId) {
        if (opId == null || opId.isEmpty()) return false;
        return locallyIssued.remove(opId);
    }

    /**
     * The line for a punishment that arrived over the tunnel, rather than one typed here.
     *
     * <p>Everything it needs is on the row, which is why a punishment issued from Discord or the
     * dashboard reads the same in chat as one typed in-game: the same issuer name, the same
     * duration, the same {@code silent} flag the bot stored. The remaining duration is computed
     * from {@code expiresAt} because the wire carries an instant rather than a length, and a ban
     * set for a week two days ago has five days left, which is the true answer to the question
     * the line is asking.
     */
    private static PunishmentAnnouncement announcementFor(ActivePunishment p,
            PunishmentSettings settings) {
        return PunishmentAnnouncement.issued(
                settings.announceIssue, viewOf(p, settings), System.currentTimeMillis());
    }

    /** Whole seconds from {@code now} to an ISO instant, or {@code null} for no expiry. */
    static Long secondsUntil(String expiresAt, long nowMillis) {
        if (expiresAt == null || expiresAt.isEmpty()) return null;
        try {
            long remaining = Instant.parse(expiresAt).toEpochMilli() - nowMillis;
            if (remaining <= 0) return null;
            return Long.valueOf(Math.max(1L, remaining / TimeUnit.SECONDS.toMillis(1)));
        } catch (RuntimeException unparseable) {
            return null;
        }
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

    /**
     * Answers the dashboard's alt list: {@code dupeip.query {uuid}} in, {@code dupeip_result
     * {uuid, alts}} back on the same envelope id.
     *
     * <p>The same question {@code /dupeip} answers in chat, over the tunnel, and deliberately from
     * the same {@link LastIpStore#altsOf} so the two cannot drift into disagreeing about who an alt
     * is.
     *
     * <h2>The wire shape</h2>
     *
     * <pre>{@code
     * {
     *   "uuid": "<the uuid asked about, echoed>",
     *   "supported": true,
     *   "known": true,
     *   "alts": [ { "uuid": "...", "name": "Alex", "lastSeenAt": 1757000000000 } ],
     *   "truncated": false
     * }
     * }</pre>
     *
     * <p>{@code name} is the last name <strong>this server</strong> saw, and nothing refreshes it:
     * a player who has since changed their Mojang name is listed under the old one until they next
     * log in. It is a label for whoever is reading. {@code uuid} is the identity, and is what the
     * bot should join on. The key is omitted entirely rather than sent empty when the store has
     * never seen a name, so "no name recorded" is distinguishable from a player called "".
     *
     * <p>{@code lastSeenAt} is epoch milliseconds, taken from the last login this server recorded.
     *
     * <p>{@code truncated} says the answer was cut at {@link #DUPEIP_ALT_LIMIT}, so the bot can say
     * "50+" rather than imply it has the whole list. Rows come newest first, which is the order
     * that makes a cut list the useful half rather than an arbitrary one.
     *
     * <h2>supported and known, and why neither is just an empty list</h2>
     *
     * <p>An empty {@code alts} has three causes that mean entirely different things, and a reply
     * that could not separate them would be read as the mildest one:
     *
     * <ul>
     *   <li>{@code supported: false} - this server holds no last-address data at all. Only a
     *       gatekeeper or a standalone server opens a {@link LastIpStore}; an enforcer never does,
     *       and neither does a server part-way through disabling the module.
     *   <li>{@code known: false} - the server has the data and looked, but has no address on file
     *       for this player: it has never seen them, or the uuid was empty or malformed. Always
     *       false when {@code supported} is, since a server with no store knows nothing.
     *   <li>both true, {@code alts} empty - the real answer. This player has been here, and nobody
     *       else has connected from the address they last used.
     * </ul>
     *
     * <p>Only the third is a finding. A dashboard that showed "no shared addresses" for the first
     * two would be offering a reassurance the server never gave, about a player nobody has ever
     * checked.
     *
     * <p>It replies in all three cases. The bot holds a correlated future regardless, and a silent
     * backend costs it the full request timeout to learn what a flag says at once. Same rule as
     * {@code RemoteRequestWiring}'s: every path ends in a reply.
     *
     * <p>Package-private so a test can hold a handler across a disable and prove the two captured
     * references below are what make that safe.
     *
     * @param tunnel captured at subscribe time rather than read from the volatile context field, so
     *     a disable racing an in-flight request cannot turn the reply into a
     *     {@code NullPointerException} and a dangling bot future
     * @param logger captured for the same reason
     */
    TunnelMessageHandler dupeipHandler(final TunnelBus tunnel, final HeimdallLogger logger) {
        return new TunnelMessageHandler() {
            @Override
            public void onMessage(final Envelope envelope) {
                String uuid = envelope.payload().string("uuid", "").trim();
                LastIpStore ips = lastIps;
                LastIpStore.Alts found = ips == null || uuid.isEmpty()
                        ? null
                        : ips.altsOf(uuid, DUPEIP_ALT_LIMIT);
                List<Payload> alts = new ArrayList<Payload>();
                if (found != null) {
                    for (int i = 0; i < found.rows.size(); i++) {
                        LastIpStore.Alt alt = found.rows.get(i);
                        Payload.Builder row = Payload.builder()
                                .put("uuid", alt.uuid == null ? "" : alt.uuid);
                        if (alt.name != null) {
                            row.put("name", alt.name);
                        }
                        alts.add(row.put("lastSeenAt", alt.seenAt).build());
                    }
                }
                Payload answer = Payload.builder()
                        .put("uuid", uuid)
                        .put("supported", ips != null)
                        .put("known", found != null && found.addressKnown)
                        .putChildren("alts", alts)
                        .put("truncated", found != null && found.truncated)
                        .build();
                try {
                    tunnel.reply(envelope.id(), DUPEIP_RESULT, answer);
                } catch (RuntimeException failed) {
                    // The tunnel can drop between a frame arriving and its answer going out.
                    // Nothing can be done about that, and the bot times out, which is the honest
                    // outcome of a dead link. It must not escape the handler as an exception.
                    logger.debug(() -> "could not reply '" + DUPEIP_RESULT + "' to request "
                            + envelope.id() + ": " + failed);
                }
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
                String key = null;
                if ("ipban".equals(type) && !digest.isEmpty()) {
                    key = "ipban:" + digest;
                } else if (!uuid.isEmpty() && !type.isEmpty()) {
                    key = type + ":" + uuid;
                }
                if (key == null) return;
                // Read before the evict: the row is the only thing that knows the player's name
                // and whether the punishment was silent, and the revoke frame carries neither.
                // Announcing an unban more loudly than the ban it lifts would undo the silence
                // the moderator asked for.
                ActivePunishment lifted = mirror.get(key);
                mirror.evict(key);
                if (lifted == null) return;
                refreshRevokeCandidacy(
                        revokeFamily(lifted.type), lifted.targetUuid, lifted.targetName);
                if (!announceableRevoke(payload.string("revokeCause", ""))) return;
                PunishmentSettings settings = PunishmentSettings.from(context.config());
                announce(PunishmentAnnouncement.revoked(settings.announceRevoke, lifted.type,
                        revokeView(lifted, lifted.targetName, payload.string("revokedBy", ""),
                                payload.string("reason", ""), lifted.silent, settings),
                        System.currentTimeMillis()));
            }
        };
    }

    /**
     * Whether this instance is the one that announces, which is a fact about its role.
     *
     * <p><strong>The outermost Heimdall instance a player is connected through owns the
     * announcement.</strong> A {@link ServerRole#GATEKEEPER} proxy and a
     * {@link ServerRole#STANDALONE} server always announce; a {@link ServerRole#ENFORCER} backend
     * never does, silent or not.
     *
     * <p>Without that rule a proxied network announces everything twice: the proxy broadcasts to
     * everybody online, the bot fans {@code punish.apply} out to the backends, and each backend
     * broadcasts to the same players again. Nothing is lost by the backend staying quiet, because
     * every player it can see is connected through the proxy, which can see them too. The rule has
     * to be local and static rather than negotiated: a backend cannot know whether the proxy in
     * front of it runs Heimdall, and a network-wide election is a lot of machinery for one chat
     * line.
     *
     * <p>A network whose proxy does <em>not</em> run Heimdall is the case this costs. Those
     * backends resolve as {@code STANDALONE} rather than {@code ENFORCER} unless an operator has
     * said otherwise, so they keep announcing; see {@code ServerRole} for how the role resolves.
     */
    private static boolean announcesHere(ServerRole role) {
        return role != ServerRole.ENFORCER;
    }

    /**
     * Shows one line to everybody who is allowed to see it.
     *
     * <h2>The audience is computed on the server thread</h2>
     *
     * <p>Called from a command handler and from the tunnel's reading thread, and the second of
     * those is the problem. <strong>Bukkit's {@code Player#hasPermission} is not safe off the main
     * thread.</strong> It walks a permissible's attachment list, which the server, LuckPerms and
     * any other permissions plugin mutate on the main thread as attachments are added, recalculated
     * and removed, so an asynchronous read can see a half-rebuilt map: usually a wrong answer,
     * occasionally a {@code ConcurrentModificationException}. A wrong answer here means a silent
     * punishment shown to somebody who does not hold the node, which nothing logs and nobody
     * reports.
     *
     * <p>So the whole body hops through {@code PlatformFacade.mainThread()} once, and the roster
     * read, the permission checks and the sends all happen there. Once per announcement rather than
     * once per player, and on Bukkit the executor runs inline when it is already on the main thread,
     * so a moderator typing {@code /ban} sees no deferral at all.
     *
     * <p><strong>Velocity and BungeeCord do not need it</strong> and are unharmed by it. Velocity's
     * {@code PermissionSubject} is answered by a {@code PermissionFunction} the proxy treats as
     * safe from any thread - it has no main thread to speak of - and BungeeCord's
     * {@code CommandSender#hasPermission} reads a synchronised permission map. On both, the hop is
     * a task on the proxy's scheduler and costs a scheduling round.
     *
     * <p>The free side-effect is that the roster snapshot is no longer taken off-thread either,
     * which is the race {@code BukkitPlayerDirectory} retries around.
     *
     * <p>{@code null} is the ordinary answer for a punishment nothing is announced for, so it is
     * accepted here rather than guarded against at every call site.
     */
    private void announce(final PunishmentAnnouncement announcement) {
        final ModuleContext ctx = this.context;
        if (ctx == null || announcement == null) return;
        if (!announcesHere(ctx.platform().role())) return;
        // One hop, then everything: the roster read, the permission checks and the sends. See the
        // javadoc above for why the permission checks are what force it.
        ctx.platform().mainThread().execute(new Runnable() {
            @Override
            public void run() {
                deliver(ctx, announcement);
            }
        });
    }

    /** The body of {@link #announce}, on the server thread. */
    private static void deliver(ModuleContext ctx, PunishmentAnnouncement announcement) {
        Collection<PlayerHandle> online;
        try {
            online = ctx.platform().players().onlinePlayers();
        } catch (RuntimeException raced) {
            // The directory is allowed to throw rather than claim the server is empty (see
            // PlayerDirectory#onlinePlayers). A lost announcement is one chat line; the punishment
            // itself has already been applied and recorded.
            ctx.logger().debug(() -> "could not read the online list to announce a punishment: "
                    + raced);
            return;
        }
        Component line = announcement.message();
        for (PlayerHandle player : online) {
            boolean visible;
            try {
                visible = announcement.visibleTo(
                        player.hasPermission(PunishmentAnnouncement.NOTIFY_PERMISSION),
                        player.hasPermission(PunishmentAnnouncement.ADMIN_PERMISSION));
            } catch (RuntimeException gone) {
                // Asking a player who left between the snapshot and the check. Silence is the
                // right answer: they are not online to be told.
                continue;
            }
            if (!visible) continue;
            try {
                player.sendMessage(line);
            } catch (RuntimeException gone) {
                // Somebody who left between the check and the send. The ordinary race, which
                // every handle already tolerates; this is the belt for a platform whose braces
                // slipped.
            }
        }
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
                rememberMirrorNames();
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
            player.sendMessage(render(punishment, settings));
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
        player.kick(render(punishment, settings));
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
        LastIpStore ips = lastIps;
        if (ips == null) {
            source.sendMessage(Msg.legacy("§e/dupeip reads this proxy's last-address file. "
                    + "Run it on the gatekeeper or a standalone server."));
            return;
        }
        String target = args.get(0);
        String uuid = ips.uuidByName(target);
        if (uuid == null) {
            PlayerHandle online = context.platform().players().byName(target).orElse(null);
            if (online != null) {
                uuid = online.uuid().toString();
            }
        }
        // altsOf and nothing else: an account is not its own alt, and the rows come back as copies
        // taken under the store's monitor, so a login landing mid-scan cannot be read half-written.
        // The old code held a live PlayerIps across three field reads and counted the target among
        // its own alts, so /dupeip Steve answered "Steve, Alex" while the tunnel said one.
        LastIpStore.Alts found = uuid == null ? null : ips.altsOf(uuid, DUPEIP_ALT_LIMIT);
        if (found == null || !found.addressKnown) {
            source.sendMessage(Msg.legacy("§eNo last address recorded for §f" + target));
            return;
        }
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < found.rows.size(); i++) {
            LastIpStore.Alt alt = found.rows.get(i);
            if (alt.name != null) {
                names.add(alt.name);
            }
        }
        source.sendMessage(Msg.legacy("§6Accounts sharing §f" + target + "§6's last address §7("
                + names.size() + (found.truncated ? "+" : "") + ")"));
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

    /**
     * Undoes one queued revoke against the mirror, after a full snapshot put its row back.
     *
     * <p>Does not touch the revoke-candidacy index, and does not need to: the only caller is
     * {@link #replayPendingWrites}, which a sync runs immediately before rebuilding the whole
     * index from the reconciled mirror. A revoke payload carries no target name to index with
     * either.
     */
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
            return Verdict.deny(render(geoBan, settings));
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
                return Verdict.deny(render(p, settings));
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
        long seconds = payload.longValue("durationSeconds", 0L);
        p.durationSeconds = seconds > 0L ? Long.valueOf(seconds) : null;
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

    /**
     * The screen a player is shown for one punishment.
     *
     * <p>Four decisions, in this order.
     *
     * <p><strong>Which template.</strong> By family - a country ban and a subnet ban are shown
     * the ban screen, because from the player's side that is what happened - and then by whether
     * it ends. An empty permanent variant means the temporary template is used for both, which
     * is the default and is correct rather than lazy: the optional-segment rule already drops
     * the Length row when there is no length.
     *
     * <p><strong>The base.</strong> Rendered first, with the same tokens, and inserted into
     * {@code {base}} as already-parsed MiniMessage. It is rendered with a value set that has no
     * {@code base} in it, so a base that refers to itself resolves to nothing rather than
     * needing a recursion limit somebody has to defend.
     *
     * <p><strong>The tokens.</strong> {@link PunishmentView} owns them, so the disconnect screen
     * and the chat announcement cannot disagree about what "Permanent ban" or "3d 4h" means.
     *
     * <p><strong>Parsing.</strong> Once, at the end, over the finished string. Values were
     * escaped on their way in, so a reason cannot restyle the screen it appears on.
     */
    static Component render(ActivePunishment p, PunishmentSettings settings) {
        long now = System.currentTimeMillis();
        PunishmentView view = viewOf(p, settings);
        // One token set, built once. It used to be built twice per render, and each build runs
        // the sanitiser over every player-supplied value - a regex loop with up to eight passes,
        // on the login thread. The base is filled before {base} is added, so it still renders
        // with no base of its own and a self-referential base still resolves to nothing.
        Template.Values values = view.tokens(now);
        String base = Template.fill(settings.screenBase, values);
        values.putRaw("base", base);
        return Template.render(settings.screenFor(familyOf(p.type), view.permanent()), values);
    }

    /** The mirror row, as the shared renderer reads a punishment. */
    private static PunishmentView viewOf(ActivePunishment p, PunishmentSettings settings) {
        return PunishmentView.builder()
                .type(p.type)
                .targetName(p.targetName)
                .staffName(p.issuedByName)
                .id(p.id)
                .reason(p.reason)
                .serverName(settings == null ? "" : settings.serverName)
                .appealUrl(settings == null ? "" : settings.appealUrl)
                .issuedAtMillis(p.issuedAtMillis())
                .expiresAtMillis(p.expiresAtMillis())
                .lengthSeconds(p.durationSeconds)
                .silent(p.silent)
                .build();
    }

    /**
     * A lifted punishment, as the revoke announcement reads it.
     *
     * <p>Three fields come from the lifting rather than from the row: the staff name is whoever
     * revoked it, the reason is the reason they gave for revoking it, and the silence is the
     * decision made about <em>this</em> announcement - which defaults to the silence of the row
     * being lifted, because announcing "Adam unbanned Steve" on a server that was never told
     * Steve was banned discloses the very thing the silent ban was hiding.
     */
    private static PunishmentView revokeView(ActivePunishment lifted, String name, String staff,
            String reason, boolean silent, PunishmentSettings settings) {
        return PunishmentView.builder()
                .type(lifted == null ? "" : lifted.type)
                .targetName(name)
                .staffName(staff)
                .id(lifted == null ? "" : lifted.id)
                .reason(reason)
                .serverName(settings == null ? "" : settings.serverName)
                .appealUrl(settings == null ? "" : settings.appealUrl)
                .silent(silent)
                .build();
    }

    /**
     * Which of the four screens a punishment type is shown on.
     *
     * <p>{@code geo} and {@code subnet} are bans as far as the player is concerned: they were
     * refused at login for where they connected from, and there is no separate screen for either
     * because writing two more templates to say "you are banned" differently helps nobody.
     */
    private static String familyOf(String type) {
        if ("mute".equals(type)) return "mute";
        if ("kick".equals(type)) return "kick";
        if ("warn".equals(type)) return "warn";
        return "ban";
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
