package com.heimdall.module.rolesync;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.model.RoleSyncDirective;
import com.heimdall.core.module.HeimdallModule;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.tunnel.Capabilities;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * Applies the bot's Discord-role snapshots to a player's LuckPerms groups.
 *
 * <h2>Two triggers, one module</h2>
 *
 * <ul>
 *   <li>A {@code role_sync} frame the bot broadcasts when somebody's Discord roles change, handled
 *       by {@link RoleSyncPushHandler}. This module owns that subscription.
 *   <li>The {@code roleSync} block on a {@code connection-attempt} answer, handled by
 *       {@link #applyOnJoin}. This module does <strong>not</strong> make that HTTP call: the
 *       whitelist module owns the login path and already has the answer in its hand, and two
 *       modules calling {@code connection-attempt} for the same join would double every login's
 *       round trips and give the bot two join records for one arrival.
 * </ul>
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
public final class HeimdallRoleSyncModule implements HeimdallModule {

    /** The module's stable identifier, used for config keys and logging. */
    public static final String ID = "rolesync";

    /**
     * The applier for the current enable cycle, or {@code null} while the module is off.
     *
     * <p>Replaced rather than reset, so a disable-then-enable cannot leave a caller holding
     * something that belongs to the previous cycle.
     */
    private volatile RoleSyncApplier applier;

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
     * {@code connection-attempt} answer carried, including {@link RoleSyncDirective#absent()} —
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
