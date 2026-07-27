package com.heimdall.core.module;

import com.heimdall.core.config.ServerRole;
import java.util.Set;

/**
 * A feature that can be switched on and off at runtime, from the dashboard.
 *
 * <h2>The contract</h2>
 *
 * <p>{@link #enable} registers things; {@link #disable} stops using them. It does <em>not</em> have
 * to unregister them: every registry a module can reach through its {@link ModuleContext} tracks
 * what that module registered, and {@code ModuleManager} unwinds all of it mechanically. A module
 * that keeps its own handles and closes them is welcome to — closing twice is a no-op — but a module
 * that forgets is not a leak. That inversion is the whole reason hot-toggling is safe: v2 had no
 * disable path at all, and a "disabled" feature was one whose listeners were still registered and
 * whose code checked a boolean on every call.
 *
 * <h2>Identity, capabilities and roles</h2>
 *
 * <p>{@link #id()} is the key in the remote-config document. {@link #capabilities()} are wire
 * identifiers the bot narrows its config push by — see {@code Capabilities}. {@link #roles()} is
 * eligibility: a module whose roles exclude this instance's resolved role never enables, whatever
 * the dashboard says, because "the proxy owns the login decision" is a fact about the deployment
 * rather than a preference.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #enable} and {@link #disable} are called on the thread driving reconciliation and are
 * never called concurrently for the same module. Both should be quick — a config push is waiting on
 * them — so a module with expensive startup work schedules it rather than doing it inline.
 *
 * <p>Throwing from {@link #enable} is survivable: the partial registrations are unwound, the module
 * is marked failed, and the plugin carries on without it.
 */
public interface HeimdallModule {

    /** The module's stable identifier, matching its key in the remote-config document. */
    String id();

    /**
     * The wire capabilities this module provides.
     *
     * <p>Aggregated across enabled modules into the {@code identify} declaration. Claiming one for a
     * module that is switched off would mean receiving settings nothing reads.
     *
     * @return never {@code null}; may be empty for a module the bot has no protocol for
     */
    Set<String> capabilities();

    /**
     * The server roles this module may run under.
     *
     * @return never {@code null}; an <strong>empty</strong> set means "any role", which is the right
     *     answer for anything that is not about who owns the login decision
     */
    Set<ServerRole> roles();

    /** Starts the module. Everything registered through {@code context} is unwound on disable. */
    void enable(ModuleContext context);

    /**
     * Stops the module.
     *
     * <p>Called before its registrations are unwound, so a module can still use them to shut down
     * cleanly. Must be safe to call after a failed {@link #enable}.
     */
    void disable();
}
