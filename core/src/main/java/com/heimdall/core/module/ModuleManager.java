package com.heimdall.core.module;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.remoteconfig.ConfigDocument;
import com.heimdall.core.remoteconfig.ConfigListener;
import com.heimdall.core.tunnel.CapabilitySource;
import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Starts and stops modules to match what the dashboard says should be running.
 *
 * <h2>Reconciliation, not commands</h2>
 *
 * <p>The only entry point that matters is {@link #reconcile(Set)}: it diffs a desired set against
 * what is running and applies the difference. Every trigger — the first config push, a hot toggle, a
 * reconnect that brings different config — goes through the same path, so there is exactly one piece
 * of code that decides what is on. A "handle the toggle event" design instead accumulates a special
 * case per trigger, and the special cases disagree.
 *
 * <h2>Unwinding is mechanical</h2>
 *
 * <p>Everything a module registers goes through its {@link ModuleContext} and is tracked, so
 * disabling one closes its subscriptions, interceptors, observers, scheduled tasks and mirrors
 * whether or not the module remembered any of them. A module that throws from
 * {@link HeimdallModule#enable} has its partial registrations unwound and is marked
 * {@link ModuleState#FAILED} — the plugin carries on without it rather than failing to boot, because
 * one broken feature should not take a server's whitelist down with it.
 *
 * <h2>Role eligibility beats configuration</h2>
 *
 * <p>A module whose {@link HeimdallModule#roles()} excludes this instance's resolved role never
 * enables, whatever the dashboard says, and says so once. "The proxy owns the login decision" is a
 * fact about the deployment, not a preference — two components both enforcing the same login is the
 * failure the role system exists to prevent.
 *
 * <h2>Capabilities</h2>
 *
 * <p>{@link #capabilities()} is the union over <em>enabled</em> modules, and it is what the tunnel
 * declares in {@code identify}.
 *
 * <p><strong>Known limitation: the declared set is a snapshot taken when the socket opened.</strong>
 * {@code identify} is sent once per connection, so a module enabled or disabled mid-connection is
 * not reflected to the bot until the next reconnect. The consequence is asymmetric and both halves
 * are tolerable today: a module switched <em>off</em> leaves the bot pushing config nothing reads,
 * which is harmless; a module switched <em>on</em> may receive no config until the tunnel next
 * reconnects, and runs on its defaults or its cache until then.
 *
 * <p>Reconnecting to re-advertise would fix it and is deliberately not done: dropping a working
 * tunnel to update metadata would make every dashboard toggle a brief outage, which is a worse
 * trade than a delayed config push. Whether the protocol should gain a live capability update
 * instead is a bot-side decision for phase 1f — it is not invented here, because a client that
 * announced capabilities in a way the bot does not understand would look correct in testing and be
 * ignored in production.
 *
 * <h2>Threading</h2>
 *
 * <p>All lifecycle transitions are serialised on one lock, so two config pushes cannot interleave
 * into a half-enabled module. {@link #capabilities()} and {@link #state(String)} are lock-free reads
 * against a volatile snapshot — the tunnel asks for capabilities on the socket's reading thread
 * during the handshake, and that must never block behind a module starting up.
 */
public final class ModuleManager implements CapabilitySource, ConfigListener {

    private final HeimdallLogger logger;
    private final ModuleEnvironment environment;
    private final ServerRole role;

    /** Registration order, which is the order modules are enabled in. */
    private final Map<String, Managed> modules = new LinkedHashMap<String, Managed>();

    private final Object lifecycleLock = new Object();

    /** The declared capability set. Recomputed under the lock, read without it. */
    private volatile Set<String> declaredCapabilities = Collections.emptySet();

    public ModuleManager(ModuleEnvironment environment) {
        if (environment == null) {
            throw new IllegalArgumentException("environment is required");
        }
        this.environment = environment;
        this.logger = environment.logger();
        this.role = environment.platform().role();
    }

    /**
     * Registers a module. Does not start it — {@link #reconcile} does.
     *
     * <p>Registration order is the enable order, which is how a module that another depends on can
     * be made to start first without inventing a dependency graph nobody has needed yet.
     */
    public void register(HeimdallModule module) {
        if (module == null || module.id() == null || module.id().isEmpty()) {
            throw new IllegalArgumentException("a module needs an id");
        }
        synchronized (lifecycleLock) {
            if (modules.containsKey(module.id())) {
                throw new IllegalStateException("a module called '" + module.id() + "' is already registered");
            }
            Managed managed = new Managed(module);
            if (!isEligible(module)) {
                managed.state = ModuleState.INELIGIBLE;
                logger.info("module '" + module.id() + "' does not run on a " + role.wireName()
                        + " server (it requires " + module.roles() + ") — it will stay off");
            }
            modules.put(module.id(), managed);
        }
    }

    /** Every registered module id, in registration order. */
    public Set<String> registeredIds() {
        synchronized (lifecycleLock) {
            return Collections.unmodifiableSet(new LinkedHashSet<String>(modules.keySet()));
        }
    }

    /** A module's current state, or {@code null} if nothing by that id is registered. */
    public ModuleState state(String moduleId) {
        synchronized (lifecycleLock) {
            Managed managed = modules.get(moduleId);
            return managed == null ? null : managed.state;
        }
    }

    /** The ids currently running. */
    public Set<String> enabledIds() {
        Set<String> enabled = new LinkedHashSet<String>();
        synchronized (lifecycleLock) {
            for (Map.Entry<String, Managed> entry : modules.entrySet()) {
                if (entry.getValue().state == ModuleState.ENABLED) {
                    enabled.add(entry.getKey());
                }
            }
        }
        return Collections.unmodifiableSet(enabled);
    }

    @Override
    public Set<String> capabilities() {
        return declaredCapabilities;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Makes the running set match {@code desired}.
     *
     * <p>Stops what should not be running before starting what should: two modules that both claim
     * the login decision must never overlap, even for the length of one reconcile.
     */
    public void reconcile(Set<String> desired) {
        Set<String> wanted = desired == null
                ? Collections.<String>emptySet()
                : new LinkedHashSet<String>(desired);
        synchronized (lifecycleLock) {
            List<String> toStop = new ArrayList<String>();
            for (Map.Entry<String, Managed> entry : modules.entrySet()) {
                if (!wanted.contains(entry.getKey()) && entry.getValue().state != ModuleState.INELIGIBLE) {
                    toStop.add(entry.getKey());
                }
            }
            for (String id : toStop) {
                stopQuietly(modules.get(id));
            }
            for (Map.Entry<String, Managed> entry : modules.entrySet()) {
                if (wanted.contains(entry.getKey())) {
                    start(entry.getValue());
                }
            }
            recomputeCapabilities();
        }
    }

    /** Reconciles against whatever the remote config currently says. */
    public void reconcileFromConfig() {
        reconcile(environment.remoteConfig().enabledModuleIds());
    }

    /**
     * Subscribes to the remote config so a dashboard toggle takes effect immediately.
     *
     * @return a handle that stops the manager reacting to config changes
     */
    public Registration followRemoteConfig() {
        return environment.remoteConfig().subscribeAll(this);
    }

    @Override
    public void onConfigChanged(ConfigDocument previous, ConfigDocument current) {
        reconcile(current.enabledModuleIds());
    }

    /** Stops everything, in reverse registration order. */
    public void shutdown() {
        synchronized (lifecycleLock) {
            List<Managed> reversed = new ArrayList<Managed>(modules.values());
            Collections.reverse(reversed);
            for (Managed managed : reversed) {
                stopQuietly(managed);
            }
            recomputeCapabilities();
        }
    }

    // ── Internals (all under lifecycleLock) ──────────────────────────────────

    private void start(Managed managed) {
        if (managed.state == ModuleState.ENABLED || managed.state == ModuleState.INELIGIBLE) {
            return;
        }
        if (managed.state == ModuleState.FAILED) {
            // Not retried while it stays wanted — see ModuleState.FAILED. Toggling it off in the
            // dashboard returns it to STOPPED, and toggling it back on tries again.
            return;
        }

        managed.registrations.reopen();
        ModuleContextImpl context =
                new ModuleContextImpl(managed.module.id(), environment, managed.registrations);
        try {
            managed.module.enable(context);
            managed.state = ModuleState.ENABLED;
            logger.info("module '" + managed.module.id() + "' enabled");
        } catch (RuntimeException e) {
            logger.error("module '" + managed.module.id() + "' failed to start and has been "
                    + "disabled; the rest of the plugin is unaffected", e);
            unwind(managed);
            managed.state = ModuleState.FAILED;
        }
    }

    /**
     * Stops one module without letting its failure reach the modules after it.
     *
     * <p>{@link #stop} already contains a module that throws from {@code disable()}, and
     * {@link TrackedRegistrations#closeAll()} guards each handle individually. This is the outer
     * belt: anything else that could escape — a handle whose {@code close()} throws something the
     * inner guard does not catch, a logger that fails — would otherwise abort the loop and leave
     * every module after this one running while the plugin believes it has shut down.
     */
    private void stopQuietly(Managed managed) {
        try {
            stop(managed);
        } catch (RuntimeException e) {
            logger.error("stopping module '" + managed.module.id() + "' failed; continuing with "
                    + "the rest", e);
            managed.state = ModuleState.STOPPED;
        }
    }

    private void stop(Managed managed) {
        if (managed.state == ModuleState.FAILED) {
            // Leaving the desired set clears the failure, so re-enabling retries.
            managed.state = ModuleState.STOPPED;
            return;
        }
        if (managed.state != ModuleState.ENABLED) {
            return;
        }
        unwind(managed);
        managed.state = ModuleState.STOPPED;
        logger.info("module '" + managed.module.id() + "' disabled");
    }

    /**
     * Calls {@code disable()} and then closes every handle the module was given.
     *
     * <p>That order matters: a module's own shutdown may need the things it registered — a relay
     * flushing a last message needs the tunnel subscription it is about to lose. A module that
     * throws on the way out still has its registrations unwound, which is the entire point of
     * tracking them.
     */
    private void unwind(Managed managed) {
        try {
            managed.module.disable();
        } catch (RuntimeException e) {
            logger.error("module '" + managed.module.id() + "' threw while stopping; unwinding "
                    + "its registrations anyway", e);
        }
        managed.registrations.closeAll();
    }

    private void recomputeCapabilities() {
        Set<String> union = new LinkedHashSet<String>();
        for (Managed managed : modules.values()) {
            if (managed.state != ModuleState.ENABLED) {
                continue;
            }
            Set<String> claimed = managed.module.capabilities();
            if (claimed != null) {
                union.addAll(claimed);
            }
        }
        declaredCapabilities = Collections.unmodifiableSet(union);
    }

    private boolean isEligible(HeimdallModule module) {
        Set<ServerRole> roles = module.roles();
        // An empty set means "any role" — the right answer for anything that is not about who owns
        // the login decision, and the answer most modules should give.
        return roles == null || roles.isEmpty() || roles.contains(role);
    }

    /** One registered module and everything the manager keeps about it. */
    private final class Managed {

        private final HeimdallModule module;
        private final TrackedRegistrations registrations;
        private ModuleState state = ModuleState.STOPPED;

        Managed(HeimdallModule module) {
            this.module = module;
            this.registrations = new TrackedRegistrations(logger, module.id());
        }
    }
}
