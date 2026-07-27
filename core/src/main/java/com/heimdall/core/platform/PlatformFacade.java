package com.heimdall.core.platform;

import com.heimdall.core.config.ServerRole;
import java.nio.file.Path;
import java.util.concurrent.Executor;

/**
 * The three things core needs from the server it is running on.
 *
 * <p><strong>Deliberately tiny, and it should stay that way for as long as possible.</strong> This
 * is the seam that keeps core platform-free, and a facade is only worth having while it is smaller
 * than the API it hides. Every method added here is a method three platform modules have to
 * implement and every feature module can reach for — so the question to answer before adding one is
 * why the platform module cannot own the behaviour itself and hand core a result.
 *
 * <p>Phase 1c grows it (audiences, player lookup, command execution) as the platform adapters land
 * and the real needs become visible. 1b needs exactly this much.
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
     * decision the proxy already made.
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
     */
    Executor mainThread();
}
