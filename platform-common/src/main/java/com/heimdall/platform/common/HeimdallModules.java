package com.heimdall.platform.common;

import com.heimdall.core.wiring.HeimdallRuntime;
import com.heimdall.module.console.HeimdallConsoleModule;
import com.heimdall.module.offenses.HeimdallOffensesModule;
import com.heimdall.module.rolesync.HeimdallRoleSyncModule;
import com.heimdall.module.whitelist.HeimdallWhitelistModule;

/**
 * Which feature modules this build ships, and how the two that know about each other are joined up.
 *
 * <h2>Why the set is written down once</h2>
 *
 * <p>The same reason {@code HeimdallRuntime} exists at all (departure D48): a module set written out
 * in both entry points is a set that eventually differs, and the difference surfaces as a feature
 * that works on Paper and not on Velocity. Neither entry point should be able to ship a different
 * plugin from the other, so neither of them holds the list.
 *
 * <p>It lives in {@code :platform-common} because that is the only module both entry points already
 * depend on. It deliberately does <strong>not</strong> live in core: core must not depend on the
 * feature modules — their being optional and independently toggleable is the whole point of the
 * module system — and it cannot live in {@code :app} either, since {@code :app} is the assembler and
 * depends on the platform modules rather than the other way round.
 *
 * <h2>Order, and the one pairing that exists</h2>
 *
 * <p>{@code ModuleManager} enables in registration order, which is how a module another depends on
 * can be started first without inventing a dependency graph. Nothing here needs that today.
 *
 * <p>The one pairing is not an ordering problem: the whitelist module hands the {@code roleSync}
 * block from a login response to the role-sync module through {@code RoleSyncSink}, and that wiring
 * is done below, before either is enabled. The sink is a live reference to a module that may itself
 * be switched off, and answering "do nothing" while disabled is the sink's own job — so the two can
 * be toggled in any order, in either direction, and this file does not care.
 */
public final class HeimdallModules {

    private HeimdallModules() {
    }

    /**
     * Registers every shipped module against a runtime that has not started yet.
     *
     * <p>Must be called between {@code build()} and {@code start()} — the gap
     * {@link HeimdallRuntime} leaves open for exactly this. Registering after {@code start()} would
     * mean the first reconcile had already run, and the module would sit {@code STOPPED} until the
     * next config push.
     */
    public static void registerAll(HeimdallRuntime runtime) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime is required");
        }

        // runtime.api() is null on a server that was never set up. Every module that takes it
        // tolerates that rather than being left unregistered: a plugin that silently shipped fewer
        // features until somebody ran the setup command would have no way to tell an operator what
        // was missing, whereas the modules answer that question themselves.
        HeimdallWhitelistModule whitelist = new HeimdallWhitelistModule(runtime.api());
        HeimdallRoleSyncModule roleSync = new HeimdallRoleSyncModule();
        whitelist.setRoleSyncSink(roleSync);

        runtime.modules().register(whitelist);
        runtime.modules().register(roleSync);
        runtime.modules().register(new HeimdallOffensesModule(runtime.api()));
        runtime.modules().register(new HeimdallConsoleModule());
    }
}
