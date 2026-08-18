package com.heimdall.platform.bukkit.adapter;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

/**
 * Whether this server has the region schedulers Folia (and Paper 1.20+) expose.
 *
 * <p><strong>Probed by capability, never by brand.</strong> "Is this Folia?" is the question that
 * looks right and is wrong in both directions: Canvas and other Folia forks answer no to a brand
 * check while having every method involved, and a future Paper that dropped the methods would
 * answer yes and then throw. Both mistakes fail at runtime on a customer's server.
 *
 * <p>The two methods asked for are {@code Server#getGlobalRegionScheduler()} and
 * {@code Entity#getScheduler()}. They arrived together, and Heimdall needs both: console dispatch
 * and delayed join-settle work go on the global region, kicks and chat go on the entity's own
 * scheduler so they follow the player across region boundaries.
 *
 * <p>Safe to call from any thread, including before the server has finished starting — a missing
 * server or a missing method is {@code false}, not an exception.
 */
public final class FoliaSupport {

    private FoliaSupport() {
    }

    /** Whether both region-scheduler methods exist on this server. */
    public static boolean hasRegionSchedulers() {
        try {
            Object server = Bukkit.getServer();
            if (server == null) {
                return false;
            }
            server.getClass().getMethod("getGlobalRegionScheduler");
            Entity.class.getMethod("getScheduler");
            return true;
        } catch (Throwable absent) {
            return false;
        }
    }
}
