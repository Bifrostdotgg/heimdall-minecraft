package com.heimdall.core.platform;

import com.heimdall.core.command.CommandRegistrar;
import com.heimdall.core.config.ServerRole;
import java.nio.file.Path;
import java.util.concurrent.Executor;

/**
 * Everything core needs from the server it is running on, and nothing else.
 *
 * <p><strong>Still deliberately small, and it should stay that way.</strong> This is the seam that
 * keeps core platform-free, and a facade is only worth having while it is smaller than the API it
 * hides. Every method added here is a method three platform modules have to implement and every
 * feature module can reach for — so the question to answer before adding one is why the platform
 * module cannot own the behaviour itself and hand core a result.
 *
 * <p>Phase 1c grew it from three methods to seven, and the shape of the growth matters: the new
 * ones return <em>focused interfaces</em> ({@link PlayerDirectory}, {@link SchedulerBridge},
 * {@link ConsoleBridge}, {@link Integrations}) rather than flattening a dozen methods onto this
 * one. A module that only needs to find a player takes a {@code PlayerDirectory} as a constructor
 * argument and is testable with four lines of fake; one that took a whole {@code PlatformFacade}
 * would need a fake for the console, the scheduler and three optional plugin integrations it never
 * calls.
 *
 * <p>Implementations must be thread-safe: modules call these from whatever thread they are on.
 */
public interface PlatformFacade {

    /**
     * The resolved role of this instance — never {@link ServerRole#AUTO}.
     *
     * <p>"Auto" is a question, and answering it needs the platform (is there a proxy in front of
     * us?), which is exactly why it is resolved here rather than in core. Module eligibility is
     * decided against this: a whitelist enforcer behind a gatekeeper must not re-run the login
     * decision the proxy already made. The policy itself is {@link InstanceRoleDetector#resolve}.
     */
    ServerRole role();

    /**
     * The plugin's own data directory, already created.
     *
     * <p>Everything Heimdall persists lives under it — the bootstrap config, the remote-config
     * cache, each module's mirrors. Modules never build a path from anywhere else, so uninstalling
     * is deleting one directory.
     */
    Path dataDirectory();

    /**
     * An executor that runs work on the server's main thread.
     *
     * <p>Almost nothing in the Bukkit API may be touched from anywhere else, so this is what a
     * module hands to {@code TunnelBus.subscribe(type, handler, executor)} when its handler has to
     * kick a player or read the online list. On a platform with no such constraint — Velocity — a
     * conforming implementation may run the task on any thread it likes, which is why this is an
     * {@link Executor} rather than a method that promises a specific thread.
     *
     * <p>For work that has to run on the thread owning a <em>particular</em> player, use
     * {@link SchedulerBridge#runOnEntityThread} instead: on a regionised server there is no single
     * main thread to hop to.
     */
    Executor mainThread();

    /** Who is online, and how to find one of them. */
    PlayerDirectory players();

    /** The server's own scheduler, for the two things Heimdall's own cannot do. */
    SchedulerBridge scheduler();

    /** Running commands as the console, and watching what it prints. */
    ConsoleBridge console();

    /**
     * Registering commands players can type, and unregistering them again.
     *
     * <p>Added in phase 1d, as a focused interface for the same reason the four above are: three
     * modules need to own a verb ({@code /offend}, {@code /linkdiscord}, {@code /link}), and a
     * module that registers one must be able to have it taken away when it is switched off — which
     * is the whole of departure D30 applied to commands.
     *
     * <p>A platform that cannot register commands returns {@link CommandRegistrar#NONE}. That is a
     * plugin with fewer verbs, not a plugin that failed to load.
     */
    CommandRegistrar commands();

    /** The optional plugins Heimdall talks to — LuckPerms, Floodgate, Trace. */
    Integrations integrations();
}
