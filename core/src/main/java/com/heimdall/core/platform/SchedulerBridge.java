package com.heimdall.core.platform;

import com.heimdall.core.util.Registration;

/**
 * The two things the <em>server's</em> scheduler can do that Heimdall's own cannot.
 *
 * <p><strong>Most periodic work does not belong here.</strong> A module's polling, a mirror's
 * flush, a retry — all of that runs on {@code heimdall-sched} through
 * {@link com.heimdall.core.module.ModuleContext#scheduleRepeating}, which is owned, named, bounded
 * and shut down with the plugin. Routing it through the platform instead would tie Heimdall's
 * timing to the server's tick loop, so a lagging server would stop syncing roles at exactly the
 * moment somebody was looking at the dashboard to find out why.
 *
 * <p>What is left is work that has to happen <em>on a specific server thread</em> because the API
 * it touches is not thread-safe, and that is what this bridge is for.
 *
 * <p>Implementations are safe to call from any thread.
 */
public interface SchedulerBridge {

    /**
     * Runs {@code task} on the thread that owns {@code player}.
     *
     * <p>On the Bukkit family that is the main server thread today, and on a regionised server
     * (Folia) it is the region thread that owns the player's chunk — which is exactly why this
     * takes a player rather than being spelled "run on the main thread". Naming the <em>owner</em>
     * rather than the thread is what lets a future Folia adapter be a platform change instead of an
     * audit of every call site.
     *
     * <p>On a platform with no such constraint — Velocity has no main thread at all — a conforming
     * implementation runs the task immediately on the calling thread. That is not a degraded
     * implementation: there is no thread to hop to, so hopping would only add latency.
     *
     * <p>A {@code null} player, or one who has since disconnected, still runs the task somewhere
     * sensible rather than dropping it: the task usually ends by telling somebody the player has
     * gone.
     */
    void runOnEntityThread(PlayerHandle player, Runnable task);

    /**
     * Runs {@code task} once, on the server's main thread, after a delay.
     *
     * <p>For the narrow case where the delay itself has to be measured in the server's time rather
     * than wall-clock — waiting a tick for a join to finish settling, most obviously. Anything
     * measured in seconds belongs on {@code heimdall-sched}.
     *
     * @return a handle that cancels the pending task; closing it after it has run is a no-op
     */
    Registration runLater(Runnable task, long delayMs);
}
