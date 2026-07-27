package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.platform.SchedulerBridge;
import com.heimdall.core.util.Registration;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Getting onto the server's main thread, and back off it.
 *
 * <p>Implements both {@link Executor} — which is what {@code PlatformFacade.mainThread()} promises
 * and what a module hands to {@code TunnelBus.subscribe} — and {@link SchedulerBridge}, because on
 * a non-regionised server they are the same thread and pretending otherwise would mean two classes
 * doing one thing.
 *
 * <h2>Already on the main thread means run now</h2>
 *
 * <p>{@code runTask} from the main thread defers to the <em>next</em> tick, which is a real
 * behaviour change for a caller that expected an executor and reasonable code that reads a result
 * afterwards. Running inline when {@code isPrimaryThread()} is what makes this an executor rather
 * than a queue.
 *
 * <h2>Disable is the interesting case</h2>
 *
 * <p>Bukkit rejects scheduling from a plugin that is disabled — with {@link IllegalStateException},
 * or {@code IllegalPluginAccessException}, depending on version and on how far through disabling it
 * is. That happens constantly during shutdown, as in-flight work finishes and tries to report back,
 * so it is caught and the task is run <em>inline</em> instead. The server is single-threaded and
 * stopping, so inline is the closest thing to the main thread still on offer, and dropping the task
 * silently is how a mirror ends up unflushed.
 */
final class BukkitMainThread implements Executor, SchedulerBridge {

    /** One tick, in milliseconds. Bukkit's scheduler counts ticks, callers count time. */
    private static final long MS_PER_TICK = 50L;

    private final Plugin plugin;
    private final HeimdallLogger logger;

    BukkitMainThread(Plugin plugin, HeimdallLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    @Override
    public void execute(Runnable command) {
        if (command == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            runGuarded(command);
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, guard(command));
        } catch (Throwable rejected) {
            logger.debug(() -> "the scheduler refused a task (the plugin is disabling); running it "
                    + "inline instead: " + rejected);
            runGuarded(command);
        }
    }

    @Override
    public void runOnEntityThread(PlayerHandle player, Runnable task) {
        // On the Bukkit family every entity is owned by the one main thread, so this is the same
        // hop. The signature names the owner rather than the thread precisely so a future Folia
        // adapter is a change here and nowhere else.
        execute(task);
    }

    @Override
    public Registration runLater(Runnable task, long delayMs) {
        if (task == null) {
            return Registration.NONE;
        }
        try {
            final BukkitTask handle = Bukkit.getScheduler()
                    .runTaskLater(plugin, guard(task), Math.max(0L, delayMs) / MS_PER_TICK);
            return Registration.once(new Runnable() {
                @Override
                public void run() {
                    handle.cancel();
                }
            });
        } catch (Throwable rejected) {
            logger.debug(() -> "not scheduling delayed work; the plugin is disabling: " + rejected);
            return Registration.NONE;
        }
    }

    /**
     * Wraps a task so one failure cannot become a server-log stack trace attributed to Heimdall.
     *
     * <p>An uncaught exception from a scheduled task is printed by Bukkit with the plugin's name on
     * it, which is both correct and unhelpful — the useful line is the one that says what Heimdall
     * was trying to do.
     */
    private Runnable guard(final Runnable task) {
        return new Runnable() {
            @Override
            public void run() {
                runGuarded(task);
            }
        };
    }

    private void runGuarded(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            logger.error("a task on the server's main thread failed", e);
        }
    }
}
