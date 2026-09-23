package com.heimdall.module.rolesync;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.http.model.RoleSyncDirective;
import com.heimdall.core.module.HeimdallModule;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.platform.LuckPermsBridge;
import com.heimdall.core.roles.RoleSyncLoginSource;
import com.heimdall.core.roles.RoleSyncSink;
import com.heimdall.core.tunnel.Capabilities;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Applies the bot's Discord-role snapshots to a player's LuckPerms groups.
 *
 * <h2>Two triggers, one module, and a join path that does not need the whitelist</h2>
 *
 * <ul>
 *   <li>A {@code role_sync} frame the bot broadcasts when somebody's Discord roles change, handled
 *       by {@link RoleSyncPushHandler}. This module owns that subscription.
 *   <li>A player joining. When the whitelist module decided the login, the {@code roleSync} block
 *       on its {@code connection-attempt} answer arrives through {@link #applyOnJoin}, and this
 *       module does <strong>not</strong> make an HTTP call of its own: two calls for the same join
 *       would double every login's round trips. When no answer is coming and this is the server
 *       that would have made that call (the whitelist module is off or failed, or the player is
 *       bypassed, on a standalone server, a proxy, or an enforcing backend),
 *       {@link JoinSnapshotRequester} asks {@code role-sync/snapshot} for the same block and hands
 *       it to the same {@link #applyOnJoin}. A backend that leaves logins to its gatekeeper does
 *       not ask: one login is one bot call per network, not per server.
 * </ul>
 *
 * <p>Until that second path existed, role sync on join depended entirely on the whitelist module:
 * a guild that wanted Discord roles mirrored into LuckPerms but ran an open server got groups only
 * from pushes, so a player whose roles changed while they were offline joined with stale groups
 * and kept them until the next change. Which case a join is in is the whitelist module's to say,
 * through {@link RoleSyncLoginSource}; that interface's javadoc records why it is not answered by
 * reading the whitelist's enabled flag from the remote config.
 *
 * <h2>{@link #roles()} is empty — this runs anywhere</h2>
 *
 * <p>Deliberately, and it is v2 parity rather than an oversight. v2 shipped a
 * {@code VelocityLuckPermsManager} alongside its Bukkit one, so it really did sync groups on the
 * proxy as well as on backends, and {@code net.luckperms:api} is platform-neutral — the same
 * artifact runs on both. Unlike the login decision, applying a group snapshot is not a thing two
 * components can fight over: the snapshot is the same on every server that reads it, and each writes
 * it to its own LuckPerms. Restricting this to {@link ServerRole#ENFORCER} would silently stop
 * working for every network whose LuckPerms lives on the proxy.
 *
 * <p>A server with no LuckPerms is therefore an ordinary configuration, not a failed enable. The
 * module starts, subscribes, and reports the absence exactly once — see {@link RoleSyncApplier}.
 *
 * <h2>v2's {@code cleanupUser} is not needed, and this is where that was decided</h2>
 *
 * <p>v2's {@code VelocityLuckPermsManager} exposed {@code cleanupUser(uuid)}, which drops a user
 * from LuckPerms' in-memory cache so the next read comes from storage. Nothing in v2 called it on a
 * schedule; it existed for a caller that needed fresh data. Departure N8 left the decision to this
 * module on the grounds that "a module that reads cached groups in a loop and never invalidates is a
 * module that acts on a stale answer indefinitely".
 *
 * <p><strong>The answer is that it is not needed, because there is no loop.</strong> This module
 * never reads groups on a cadence — it has no polling at all. It reacts to a pushed snapshot and to
 * a login answer, and in both cases the snapshot is the bot's, carried on the event, not something
 * read back out of LuckPerms. The only read of LuckPerms' own state is the one
 * {@link com.heimdall.core.platform.LuckPermsBridge#setPlayerGroups} does internally to compute the
 * diff, and that loads the user from storage when it is not cached — which is the behaviour
 * {@code cleanupUser} exists to force. Invalidating beforehand would buy nothing and cost a storage
 * round trip on every sync.
 *
 * <p>The condition that would change the answer is worth writing down, because it is the shape the
 * 2.4.0 outage took (departure D7): <em>if this module ever grows a periodic reconcile that reads
 * current groups and compares them against a snapshot it is holding, it needs an invalidation
 * step</em>, and {@code LuckPermsBridge} needs a method for it. Until then, adding one would be
 * adding a cache-management API with no cache to manage.
 *
 * <h2>Settings: none, deliberately</h2>
 *
 * <p>{@code enabled} is the manager's business, and nothing else about this module is worth a knob.
 * v2 had no role-sync settings, the dashboard sends none, and the only candidate — the two-second
 * join defer — would be a field permanently reading its own default while presenting as an option
 * an operator could change. If one is ever added it goes in with the dashboard field in the same
 * change, and it is read through {@link ModuleContext#settings()} at the point of use rather than
 * captured in {@link #enable}, because a settings change does not re-enable a module.
 *
 * <h2>Threading and ownership</h2>
 *
 * <p>{@link #enable} and {@link #disable} are called by {@code ModuleManager} on the reconciliation
 * thread and never concurrently for this module. Everything else — the tunnel subscription's
 * handler, {@link #applyOnJoin} — arrives on other threads, so the one piece of mutable state here
 * is a {@code volatile} reference to the {@link RoleSyncApplier} that is live right now, replaced on
 * enable and cleared on disable.
 *
 * <p>That reference is what makes the public entry point safe to hold: a caller (the whitelist
 * module, or phase 1e) keeps a handle on the module itself and calls {@link #applyOnJoin} whenever
 * it has an answer, without knowing or caring whether role sync is currently switched on. When it is
 * off the call is a no-op, which is the correct answer and the one that does not require every
 * caller to check first.
 *
 * <p>The tunnel subscription is made through {@link ModuleContext}, so {@code ModuleManager} unwinds
 * it whether or not this class remembers to (departure D30). {@link #disable} is safe after a failed
 * {@link #enable} — it has nothing but a possibly-null reference to clear — and it additionally
 * cancels deferred join syncs, which live in the <em>server's</em> scheduler and are therefore the
 * one thing the tracked registrations do not cover.
 */
public final class HeimdallRoleSyncModule implements HeimdallModule, RoleSyncSink {

    /** The module's stable identifier, used for config keys and logging. */
    public static final String ID = "rolesync";

    /**
     * The applier for the current enable cycle, or {@code null} while the module is off.
     *
     * <p>Replaced rather than reset, so a disable-then-enable cannot leave a caller holding
     * something that belongs to the previous cycle.
     */
    private volatile RoleSyncApplier applier;

    /**
     * Who already delivers a directive on login, and so makes the join-time request redundant.
     *
     * <p>Set by the wiring, like the whitelist module's {@code setRoleSyncSink}, and for the same
     * reason: neither module may depend on the other. {@link RoleSyncLoginSource#NONE} until then,
     * which is also the right answer on a build with no whitelist module: there is no login check,
     * so the server's role decides. A standalone server or a proxy asks on every join; an
     * {@code ENFORCER} backend does not, because its gatekeeper is the one that would have made the
     * login call. See {@link JoinSnapshotRequester}.
     */
    private volatile RoleSyncLoginSource loginSource = RoleSyncLoginSource.NONE;

    /** Wires in whoever answers logins. Called once by the runtime, before anything is enabled. */
    public void setLoginSource(RoleSyncLoginSource source) {
        this.loginSource = source == null ? RoleSyncLoginSource.NONE : source;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> capabilities() {
        return Collections.singleton(Capabilities.ROLE_SYNC);
    }

    @Override
    public Set<ServerRole> roles() {
        // Empty means "any role" — see the class javadoc for why that is the v2-parity answer.
        return Collections.emptySet();
    }

    @Override
    public void enable(ModuleContext context) {
        RoleSyncApplier started = new RoleSyncApplier(context);
        this.applier = started;
        // The default overload dispatches on heimdall-io, which is where this handler wants to be:
        // it must not run on the socket's reading thread, and it has no reason to be on the main
        // server thread — LuckPermsBridge does its own hopping.
        context.tunnel().subscribe(RoleSyncPushHandler.MESSAGE_TYPE,
                new RoleSyncPushHandler(context, started));
        // Tracked by the context like the subscription, so a disable unwinds it. Runs on
        // heimdall-io and only fires a request, so a join is never held up by the bot.
        final ModuleContext ctx = context;
        context.onPlayerJoin(new JoinSnapshotRequester(
                context.logger(),
                new Supplier<HeimdallApi>() {
                    @Override
                    public HeimdallApi get() {
                        return ctx.api();
                    }
                },
                new Supplier<RoleSyncLoginSource>() {
                    @Override
                    public RoleSyncLoginSource get() {
                        return loginSource;
                    }
                },
                new Supplier<ServerRole>() {
                    @Override
                    public ServerRole get() {
                        return ctx.platform().role();
                    }
                },
                new Supplier<LuckPermsBridge>() {
                    @Override
                    public LuckPermsBridge get() {
                        // Quiet on purpose: the absence warning belongs to the apply path.
                        Optional<LuckPermsBridge> found = ctx.platform().integrations().luckPerms();
                        LuckPermsBridge bridge = found.isPresent() ? found.get() : null;
                        return bridge != null && bridge.isAvailable() ? bridge : null;
                    }
                },
                this));
    }

    @Override
    public void disable() {
        RoleSyncApplier running = this.applier;
        this.applier = null;
        if (running != null) {
            running.shutdown();
        }
    }

    /**
     * Applies the {@code roleSync} block that came back with a login answer.
     *
     * <p><strong>This is the module's service API</strong>, and the only supported way in from
     * another module. The whitelist module calls it once per admitted login with whatever the
     * {@code connection-attempt} answer carried, and this module's own join-time snapshot request
     * calls it the same way when no such answer is coming. Both pass
     * {@link RoleSyncDirective#absent()} through as well:
     * passing the tri-state through rather than pre-filtering it is what keeps the "why did nothing
     * happen" answer in one place.
     *
     * <p>Returns immediately. The sync itself is deferred so the player is fully connected first,
     * and then runs against LuckPerms without blocking anything.
     *
     * <p>Safe from any thread, and a no-op — not an error — when this module is disabled, when
     * LuckPerms is absent, when the directive is absent or disabled, or when it names no managed
     * groups.
     *
     * @param uuid the joining player
     * @param username their name, used for log lines
     * @param directive the {@code roleSync} block, never pre-collapsed to a boolean
     */
    @Override
    public void applyOnJoin(UUID uuid, String username, RoleSyncDirective directive) {
        RoleSyncApplier running = this.applier;
        if (running == null) {
            return;
        }
        running.applyOnJoin(uuid, username, directive);
    }

    /** The live applier, or {@code null} when the module is off. For tests. */
    RoleSyncApplier applier() {
        return applier;
    }
}
