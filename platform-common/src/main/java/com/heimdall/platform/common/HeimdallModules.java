package com.heimdall.platform.common;

import com.heimdall.core.admin.AdminContext;
import com.heimdall.core.wiring.HeimdallRuntime;
import com.heimdall.module.bridge.HeimdallBridgeModule;
import com.heimdall.module.console.HeimdallConsoleModule;
import com.heimdall.module.offenses.HeimdallOffensesModule;
import com.heimdall.module.rolesync.HeimdallRoleSyncModule;
import com.heimdall.module.whitelist.HeimdallWhitelistModule;

/**
 * Which feature modules this build ships, how the two that know about each other are joined up, and
 * how the admin command reaches them.
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
 * <h2>The same reasoning is why the admin surfaces are introduced here</h2>
 *
 * <p>Three admin verbs — {@code test}, {@code cache}, {@code offense} — are about a specific module,
 * and core cannot name one. So core declares small interfaces, the modules implement them, and this
 * class, which is the one place that already depends on both sides, hands the implementations to the
 * command tree. A module absent from a build simply never arrives, and the command tree falls back
 * to its {@code NONE} implementations rather than losing a verb.
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
     * Registers every shipped module against a runtime that has not started yet, and returns the
     * admin surfaces two of them expose.
     *
     * <p>Must be called between {@code build()} and {@code start()} — the gap
     * {@link HeimdallRuntime} leaves open for exactly this. Registering after {@code start()} would
     * mean the first reconcile had already run, and the module would sit {@code STOPPED} until the
     * next config push.
     *
     * <p>No module takes an API client any more. Each reaches the bot through
     * {@code ModuleContext.api()}, which is one gateway core re-points as the server is set up and
     * as its guild resolves — so a module registered here on an unconfigured server is still holding
     * the right thing after {@code /hd setup} runs, without a restart. Departure D56.
     *
     * @param admin the context the command tree will be built from; this fills in the module
     *     surfaces on it and leaves everything else to the caller
     */
    public static void registerAll(HeimdallRuntime runtime, AdminContext.Builder admin) {
        if (runtime == null || admin == null) {
            throw new IllegalArgumentException("a runtime and an admin context builder are required");
        }

        HeimdallWhitelistModule whitelist = new HeimdallWhitelistModule();
        HeimdallRoleSyncModule roleSync = new HeimdallRoleSyncModule();
        HeimdallOffensesModule offenses = new HeimdallOffensesModule();
        whitelist.setRoleSyncSink(roleSync);

        runtime.modules().register(whitelist);
        runtime.modules().register(roleSync);
        runtime.modules().register(offenses);
        runtime.modules().register(new HeimdallConsoleModule());
        // The Discord chat bridge. Registered like every other module and eligible on every role —
        // whether an instance relays its own chat is its `relayChat` setting rather than an
        // eligibility rule, so a proxy is registered here exactly as a backend is. Departure D79.
        runtime.modules().register(new HeimdallBridgeModule());

        // The modules themselves implement the admin interfaces. A separate adapter object would
        // only be a place for the two to disagree about whether a module is running.
        admin.whitelist(whitelist).offenses(offenses);
    }
}
