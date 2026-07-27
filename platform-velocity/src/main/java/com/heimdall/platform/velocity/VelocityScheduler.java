package com.heimdall.platform.velocity;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.platform.SchedulerBridge;
import com.heimdall.core.util.Registration;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * The proxy has no main thread, and this is what that means in practice.
 *
 * <p>{@code PlatformFacade.mainThread()} promises an {@link Executor}, deliberately, rather than a
 * method that guarantees a particular thread — because on Velocity there is no such thread to
 * guarantee. Every Velocity API is safe from anywhere, so the conforming implementation here runs
 * the task <strong>immediately, on the calling thread</strong>.
 *
 * <p>That is not a degraded implementation. Hopping would add scheduling latency to a role sync in
 * exchange for nothing, and it would give a module author the false impression that some ordering
 * had been established. A module that needs work serialised has to say so itself; the platform
 * cannot do it for them here and does not pretend to.
 *
 * <p>{@link #runLater} is the one method that genuinely needs the proxy's scheduler, because the
 * task has to be cancellable when the plugin unloads and Velocity cancels a plugin's tasks for it.
 */
final class VelocityScheduler implements Executor, SchedulerBridge {

    private final Object plugin;
    private final ProxyServer proxy;
    private final HeimdallLogger logger;

    VelocityScheduler(Object plugin, ProxyServer proxy, HeimdallLogger logger) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
    }

    @Override
    public void execute(Runnable command) {
        if (command == null) {
            return;
        }
        try {
            command.run();
        } catch (RuntimeException e) {
            // Contained for the same reason the Bukkit side contains it: this is what a module hands
            // to TunnelBus.subscribe, and an exception escaping would be attributed to the tunnel.
            logger.error("a task on the proxy's platform executor failed", e);
        }
    }

    @Override
    public void runOnEntityThread(PlayerHandle player, Runnable task) {
        execute(task);
    }

    @Override
    public Registration runLater(Runnable task, long delayMs) {
        if (task == null) {
            return Registration.NONE;
        }
        try {
            ScheduledTask handle = proxy.getScheduler()
                    .buildTask(plugin, task)
                    .delay(Math.max(0L, delayMs), TimeUnit.MILLISECONDS)
                    .schedule();
            return Registration.once(handle::cancel);
        } catch (RuntimeException rejected) {
            logger.debug(() -> "not scheduling delayed work; the proxy is shutting down: " + rejected);
            return Registration.NONE;
        }
    }
}
