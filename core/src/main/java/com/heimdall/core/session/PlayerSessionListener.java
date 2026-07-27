package com.heimdall.core.session;

import com.heimdall.core.platform.PlayerHandle;

/**
 * Told that a player joined, or that one left.
 *
 * <p>One interface for both directions rather than two, because every consumer so far does the same
 * thing with a different number: the whitelist mirror slides an entry forward by its join window or
 * its leave window. Two interfaces would be two identical method signatures with different names.
 *
 * <h2>Threading</h2>
 *
 * <p>Listeners are invoked on {@code heimdall-io}, never on the thread the platform delivered the
 * event on — see {@link PlayerSessionEvents}. So a listener may block, and must not assume it is on
 * a server thread: anything touching the Bukkit API goes through
 * {@code PlatformFacade.mainThread()} or {@code SchedulerBridge.runOnEntityThread}.
 *
 * <p>A listener that throws is contained and logged; it does not stop the listeners after it.
 */
public interface PlayerSessionListener {

    /**
     * @param player the player who joined or left — already gone, in the quit case
     * @param timestampMs when the platform observed the event, epoch millis. Passed rather than
     *     read here because the dispatch is asynchronous: by the time this runs, "now" is some
     *     unknown distance after the thing that happened.
     */
    void onPlayerSession(PlayerHandle player, long timestampMs);
}
