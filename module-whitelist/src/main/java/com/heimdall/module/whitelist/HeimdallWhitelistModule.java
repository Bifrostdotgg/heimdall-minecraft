package com.heimdall.module.whitelist;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.ApiClient;
import com.heimdall.core.module.HeimdallModule;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.remoteconfig.ModuleConfig;
import com.heimdall.core.remoteconfig.ModuleConfigListener;
import com.heimdall.core.roles.RoleSyncSink;
import com.heimdall.core.tunnel.Capabilities;
import java.util.Collections;
import java.util.Set;
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
 * <h2>Threading and ownership</h2>
 *
 * <p>Everything is registered through {@code ModuleContext} and unwound mechanically on disable: the
 * interceptor, both session listeners, both scheduled polls, the command, and the mirror — which is
 * flushed as it closes. This class therefore keeps no handles. What it does keep is the
 * collaborators, and they are rebuilt on every enable so a toggle cannot leave an interceptor
 * holding a store that has already been shut.
 */
public final class HeimdallWhitelistModule implements HeimdallModule {

    /** The module's stable identifier, and its key in the remote-config document. */
    public static final String ID = "whitelist";

    private final ApiClient api;

    /**
     * Where a login response's role snapshot goes.
     *
     * <p>Volatile and settable because the wiring builds both modules and neither can be constructed
     * before the other. {@link RoleSyncSink#NONE} until it is set, which is also the right value on
     * a build with no role-sync module at all.
     */
    private volatile RoleSyncSink roleSync = RoleSyncSink.NONE;

    private volatile WhitelistMirrorService mirror;
    private volatile long openedWithCacheWindowMs;
    private volatile long openedWithMaxExtensionHours;

    /**
     * @param api the HTTP client, or {@code null} on a server that was never set up. Null is a
     *     supported state rather than a guard clause at every call site: the module still enables,
     *     the mirror still serves what it holds, and the fallback mode decides logins — which is
     *     exactly what a server whose bot is unreachable needs anyway.
     */
    public HeimdallWhitelistModule(ApiClient api) {
        this.api = api;
    }

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
                ctx.logger(), api, WhitelistMirrorService.openMirror(ctx, atOpen), settings);
        ConnectionAttemptReporter reporter = new ConnectionAttemptReporter(
                ctx.logger(), api, ctx.platform(), ctx.executors().io(),
                new Supplier<RoleSyncSink>() {
                    @Override
                    public RoleSyncSink get() {
                        return roleSync;
                    }
                });
        this.mirror = mirrorService;

        ctx.interceptLogin(
                new WhitelistLoginInterceptor(
                        ctx.logger(), ctx.platform().role(), settings, mirrorService, reporter,
                        new Supplier<Boolean>() {
                            @Override
                            public Boolean get() {
                                return Boolean.valueOf(ctx.config().enabled());
                            }
                        }),
                WhitelistLoginInterceptor.PRIORITY);

        ctx.onPlayerJoin(mirrorService.onJoin());
        ctx.onPlayerQuit(mirrorService.onQuit());
        schedulePolls(ctx, atOpen, mirrorService);
        ctx.registerCommand(new LinkDiscordCommand(ctx.logger(), api).spec());

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
        // closes. Dropping the reference is what stops a re-enable from finding a store that has
        // already been shut. Safe after a failed enable, where it is already null.
        mirror = null;
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

    // ── For the runtime, and for phase 1e ────────────────────────────────────

    /**
     * A one-line description of the mirror, for a status command.
     *
     * <p>Answers even while the module is disabled, because "it is off" is a perfectly good thing
     * for {@code /hd status} to print and throwing would make every caller branch first.
     */
    public String mirrorStats() {
        WhitelistMirrorService live = mirror;
        return live == null ? "whitelist module is not enabled" : live.stats();
    }

    /** Runs the pre-warm poll now. What {@code /hd whitelist sync} calls in 1e. Blocking. */
    public void syncNow() {
        WhitelistMirrorService live = mirror;
        if (live != null) {
            live.syncNow();
        }
    }
}
