package com.heimdall.module.whitelist;

import com.heimdall.core.admin.LoginProbe;
import com.heimdall.core.admin.WhitelistAdmin;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.module.HeimdallModule;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.pipeline.LoginAttempt;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.remoteconfig.ModuleConfig;
import com.heimdall.core.remoteconfig.ModuleConfigListener;
import com.heimdall.core.roles.RoleSyncSink;
import com.heimdall.core.tunnel.Capabilities;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The login gate, the local whitelist mirror, and {@code /linkdiscord}.
 *
 * <h2>It runs on every role, and a backend can opt out</h2>
 *
 * <p>{@link #roles()} is empty — any role — which is v2's behaviour: v2 had no role concept and the
 * check ran wherever the plugin was installed. Whether a <em>backend</em> behind a gatekeeper should
 * re-run a decision the proxy already made is a real question, but one with a per-deployment answer,
 * so it is the {@code enforceOnBackend} setting rather than a hard {@code roles()} exclusion.
 * Excluding the role here would mark the module {@code INELIGIBLE} and no dashboard toggle could
 * bring it back; a setting can be flipped either way at runtime.
 *
 * <p>The default is on, and the deployment that justifies it is the one that actually breaks: a
 * backend whose proxy does <em>not</em> have Heimdall installed. Turning enforcement off there opens
 * the server to anybody who can reach its port directly.
 *
 * <h2>What is read live, and the one thing that is not</h2>
 *
 * <p>Everything goes through {@link WhitelistSettings}, constructed at the point of use, so a
 * dashboard edit applies immediately. One exception, and it is documented rather than hidden: the
 * mirror's base window and its #771 extension ceiling are baked into the {@code MirrorPolicy} when
 * the store is opened, so changing {@code cacheWindow} or {@code maxExtensionHours} takes effect the
 * next time the module is enabled — switching it off and on in the dashboard is enough. Reopening
 * the store in place was rejected: two {@code MirrorStore}s over one file, one with a debounced
 * write still pending, is a way to lose the file. The module logs when it sees such a change, so an
 * operator is not left wondering why a saved value did nothing.
 *
 * <h2>The API arrives through the context, not the constructor</h2>
 *
 * <p>It used to be a constructor argument that was {@code null} on a server nobody had set up, and
 * that is what made {@code /hd setup} unable to work without a restart: the reference was captured
 * once, before anything could have configured it, and nothing could re-hand a live one. Since 1e it
 * is {@link ModuleContext#api()}, which is one stable gateway that core re-points underneath — see
 * departure D56.
 *
 * <h2>Threading and ownership</h2>
 *
 * <p>Everything is registered through {@code ModuleContext} and unwound mechanically on disable: the
 * interceptor, both session listeners, both scheduled polls, the command, and the mirror — which is
 * flushed as it closes. This class therefore keeps no handles. What it does keep is the
 * collaborators, and they are rebuilt on every enable so a toggle cannot leave an interceptor
 * holding a store that has already been shut.
 */
public final class HeimdallWhitelistModule implements HeimdallModule, WhitelistAdmin {

    /** The module's stable identifier, and its key in the remote-config document. */
    public static final String ID = "whitelist";

    /**
     * Where a login response's role snapshot goes.
     *
     * <p>Volatile and settable because the wiring builds both modules and neither can be constructed
     * before the other. {@link RoleSyncSink#NONE} until it is set, which is also the right value on
     * a build with no role-sync module at all.
     */
    private volatile RoleSyncSink roleSync = RoleSyncSink.NONE;

    private volatile WhitelistMirrorService mirror;
    private volatile WhitelistLoginInterceptor interceptor;
    private volatile PlatformFacade platform;
    private volatile long openedWithCacheWindowMs;
    private volatile long openedWithMaxExtensionHours;

    /** Wires the role-sync module in. Called once by the runtime, before anything is enabled. */
    public void setRoleSyncSink(RoleSyncSink sink) {
        this.roleSync = sink == null ? RoleSyncSink.NONE : sink;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> capabilities() {
        return Collections.singleton(Capabilities.WHITELIST);
    }

    @Override
    public Set<ServerRole> roles() {
        // Empty means any role — see the class javadoc. The backend question is a setting rather
        // than an eligibility rule, because an eligibility rule cannot be undone from the dashboard.
        return Collections.emptySet();
    }

    @Override
    public void enable(final ModuleContext ctx) {
        final Supplier<WhitelistSettings> settings = new Supplier<WhitelistSettings>() {
            @Override
            public WhitelistSettings get() {
                return new WhitelistSettings(ctx.settings());
            }
        };

        WhitelistSettings atOpen = settings.get();
        openedWithCacheWindowMs = atOpen.cacheWindowMs();
        openedWithMaxExtensionHours = atOpen.maxExtensionHours();

        final WhitelistMirrorService mirrorService = new WhitelistMirrorService(
                ctx.logger(), ctx.api(), WhitelistMirrorService.openMirror(ctx, atOpen), settings);
        ConnectionAttemptReporter reporter = new ConnectionAttemptReporter(
                ctx.logger(), ctx.api(), ctx.platform(), ctx.executors().io(),
                new Supplier<RoleSyncSink>() {
                    @Override
                    public RoleSyncSink get() {
                        return roleSync;
                    }
                });
        WhitelistLoginInterceptor gate = new WhitelistLoginInterceptor(
                ctx.logger(), ctx.platform().role(), settings, mirrorService, reporter,
                new Supplier<Boolean>() {
                    @Override
                    public Boolean get() {
                        return Boolean.valueOf(ctx.config().enabled());
                    }
                });
        this.mirror = mirrorService;
        this.interceptor = gate;
        this.platform = ctx.platform();

        ctx.interceptLogin(gate, WhitelistLoginInterceptor.PRIORITY);

        ctx.onPlayerJoin(mirrorService.onJoin());
        ctx.onPlayerQuit(mirrorService.onQuit());
        schedulePolls(ctx, atOpen, mirrorService);
        ctx.registerCommand(new LinkDiscordCommand(ctx.logger(), ctx.api()).spec());

        ctx.onConfigChanged(new ModuleConfigListener() {
            @Override
            public void onModuleConfigChanged(
                    String moduleId, ModuleConfig previous, ModuleConfig current) {
                warnIfMirrorShapeChanged(ctx, settings.get());
            }
        });

        ctx.logger().info("whitelist enabled — role " + ctx.platform().role().wireName()
                + ", fallback " + atOpen.apiFallbackMode()
                + ", mirror " + mirrorService.stats());
    }

    @Override
    public void disable() {
        // Nothing to undo — every handle belongs to the context, and the mirror is flushed as it
        // closes. Dropping the references is what stops a re-enable from finding a store that has
        // already been shut, and what makes the WhitelistAdmin surface below answer "not running"
        // rather than acting on a dead one. Safe after a failed enable, where they are already null.
        mirror = null;
        interceptor = null;
        platform = null;
    }

    /**
     * The pre-warm poll and the expiry sweep, both on {@code heimdall-sched}.
     *
     * <p>The pre-warm runs <strong>immediately</strong> as well as on its interval. A server that has
     * just started is exactly the one whose mirror is most likely to be stale, and waiting out the
     * first five-minute period is five minutes in which a bot outage refuses everybody the mirror
     * has forgotten — which is the failure the whole pre-warm design exists to prevent.
     */
    private void schedulePolls(
            ModuleContext ctx, WhitelistSettings atOpen, final WhitelistMirrorService mirrorService) {
        long prewarmMs = TimeUnit.MINUTES.toMillis(atOpen.prewarmIntervalMinutes());
        ctx.scheduleRepeating(new Runnable() {
            @Override
            public void run() {
                mirrorService.syncNow();
            }
        }, 0L, prewarmMs);

        long sweepMs = TimeUnit.MINUTES.toMillis(atOpen.cleanupIntervalMinutes());
        ctx.scheduleRepeating(new Runnable() {
            @Override
            public void run() {
                mirrorService.sweepExpired();
            }
        }, sweepMs, sweepMs);
    }

    /**
     * Says so when a setting that is fixed at mirror-open time has been changed.
     *
     * <p>The alternative to this line is an operator editing {@code cacheWindow}, seeing it saved,
     * and getting the old behaviour with nothing anywhere to explain it. A setting that appears to
     * apply and does not is worse than one that says when it will.
     */
    private void warnIfMirrorShapeChanged(ModuleContext ctx, WhitelistSettings now) {
        if (now.cacheWindowMs() != openedWithCacheWindowMs
                || now.maxExtensionHours() != openedWithMaxExtensionHours) {
            ctx.logger().warn("cacheWindow or maxExtensionHours changed, and both are fixed when the "
                    + "mirror is opened — switch this module off and on again for them to apply");
        }
    }

    // ── WhitelistAdmin: what /hd cache and /hd test reach ────────────────────

    @Override
    public boolean isAvailable() {
        return mirror != null;
    }

    /**
     * A one-line description of the mirror, for a status command.
     *
     * <p>Answers even while the module is disabled, because "it is off" is a perfectly good thing
     * for {@code /hd status} to print and throwing would make every caller branch first.
     *
     * <p>Carries the age of the last successful sync as well as the counts — see
     * {@link WhitelistMirrorService#adminStats()} for why the age is the part that answers the
     * question an operator is really asking.
     */
    @Override
    public String stats() {
        WhitelistMirrorService live = mirror;
        return live == null ? "the whitelist module is not running" : live.adminStats();
    }

    /**
     * The store's own counts, without the sync age.
     *
     * <p>Separate from {@link #stats()} because it is what the tests assert against, and a line that
     * ends in "last synced 3s ago" is not something an equality assertion can hold still. Nothing in
     * production reads it; {@code /hd status} and {@code /hd cache stats} both want the age.
     */
    String mirrorStats() {
        WhitelistMirrorService live = mirror;
        return live == null ? "whitelist module is not enabled" : live.stats();
    }

    @Override
    public void clear() {
        WhitelistMirrorService live = mirror;
        if (live != null) {
            live.clear();
        }
    }

    @Override
    public int cleanup() {
        WhitelistMirrorService live = mirror;
        return live == null ? 0 : live.sweepExpired();
    }

    /** Runs the pre-warm poll now. What {@code /hd cache sync} calls. Blocking. */
    @Override
    public void syncNow() {
        WhitelistMirrorService live = mirror;
        if (live != null) {
            live.syncNow();
        }
    }

    /**
     * Runs the whole login path for a player and reports what it decided, writing nothing.
     *
     * <p>Blocking. The translation from the interceptor's internal outcome to the platform-free
     * {@link LoginProbe} happens here rather than in core, so the admin tree never learns the
     * interceptor's vocabulary and this module stays free to change it.
     */
    @Override
    public LoginProbe probe(String playerName) {
        WhitelistLoginInterceptor gate = interceptor;
        if (gate == null) {
            return WhitelistAdmin.NONE.probe(playerName);
        }
        LoginAttempt attempt = attemptFor(playerName);
        WhitelistLoginInterceptor.Outcome outcome = gate.evaluate(attempt, false);
        WhitelistMirrorService live = mirror;
        return LoginProbe.forPlayer(attempt.username(), attempt.uuid().toString())
                // Abstain counts as allowed here, and it is the honest reading: a bypassed player
                // or a backend that leaves the decision to its gatekeeper is one this check will
                // not stop. Reporting "abstain" to an operator asking whether somebody can join
                // would answer a question about the pipeline rather than about the player.
                .allowed(!outcome.verdict().isDeny())
                .stage(outcome.stage())
                .message(outcome.message())
                .queuePosition(outcome.queuePosition())
                .mirrored(live != null && live.isWhitelisted(attempt.uuid()))
                .build();
    }

    /**
     * Builds the login this probe pretends to be.
     *
     * <p>Three sources, in descending order of how much they can be trusted:
     *
     * <ol>
     *   <li><strong>An online player</strong> contributes their real UUID and their real address,
     *       which is the only way the probe can be about the player rather than about a name.
     *   <li><strong>The mirror</strong>, which is this plugin's own copy of the bot's uuid-to-name
     *       mapping. Anybody on the whitelist is in it, which covers the case an operator actually
     *       has: "why can this person not get in?"
     *   <li><strong>The offline-mode UUID</strong>, vanilla's own {@code OfflinePlayer:<name>}
     *       derivation. Exact on a cracked server and about nobody at all on a premium one, which is
     *       why {@link LoginProbe#uuid()} reports whichever was used — an operator can see for
     *       themselves that the answer is about a derived identity.
     * </ol>
     *
     * <p>Asking Mojang is deliberately not a fourth option. The bot already holds the mapping, the
     * plugin has no business making that call, and a fabricated identity is exactly the failure
     * departure D58 is about.
     */
    private LoginAttempt attemptFor(String playerName) {
        PlatformFacade facade = platform;
        if (facade != null) {
            PlayerHandle online = facade.players().byName(playerName).orElse(null);
            if (online != null) {
                return LoginAttempt.builder(online.uuid())
                        .username(online.name())
                        .ipAddress("127.0.0.1")
                        .build();
            }
        }
        WhitelistMirrorService live = mirror;
        UUID mirrored = live == null ? null : live.uuidForName(playerName);
        if (mirrored != null) {
            return LoginAttempt.builder(mirrored)
                    .username(playerName)
                    .ipAddress("127.0.0.1")
                    .build();
        }
        return LoginAttempt.builder(offlineUuid(playerName))
                .username(playerName)
                .ipAddress("127.0.0.1")
                .build();
    }

    /**
     * Minecraft's offline-mode UUID for a name: {@code UUID.nameUUIDFromBytes("OfflinePlayer:" + name)}.
     *
     * <p>Vanilla's own derivation, so on a cracked server this is the player's real id and the probe
     * is exact. On a premium server it is not, which is why the command prints the UUID it used.
     */
    private static UUID offlineUuid(String playerName) {
        return UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
    }

    // ── For tests ────────────────────────────────────────────────────────────

    /**
     * Records a player in the mirror directly.
     *
     * <p>Package-private and for tests only: the states worth exercising include "warm mirror, no
     * bot", which by definition cannot be reached by asking a bot. Production writes to the mirror
     * only through the login path, where the bot has just vouched for the player.
     */
    void recordForTest(UUID uuid, String username) {
        WhitelistMirrorService live = mirror;
        if (live != null) {
            live.record(uuid, username);
        }
    }
}
