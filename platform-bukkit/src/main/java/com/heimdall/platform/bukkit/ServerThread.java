package com.heimdall.platform.bukkit;

import com.heimdall.core.platform.SchedulerBridge;
import java.util.concurrent.Executor;

/**
 * The one object that is both {@link com.heimdall.core.platform.PlatformFacade#mainThread()} and
 * {@link com.heimdall.core.platform.PlatformFacade#scheduler()} on the Bukkit family.
 *
 * <p>On a single-threaded server those are the same thread, and {@link BukkitMainThread} is both.
 * On a regionised server they are not: {@link #execute} hops to the global region, and
 * {@link #runOnEntityThread} hops to the region that owns the player. One type so
 * {@link BukkitPlatform} can hold one field either way.
 */
interface ServerThread extends Executor, SchedulerBridge {

    /** What to put on the enable banner — {@code bukkit} or {@code folia}. */
    String describe();
}
