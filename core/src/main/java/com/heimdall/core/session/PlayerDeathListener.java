package com.heimdall.core.session;

import com.heimdall.core.platform.PlayerHandle;

/**
 * Told that a player died, and what the server said about it.
 *
 * <h2>Why this is not {@link PlayerSessionListener} with a third verb</h2>
 *
 * <p>Join and quit share one interface because every consumer does the same thing with a different
 * number. A death carries something neither of those has — the server's own death message — and
 * bending it into {@code onPlayerSession(player, timestampMs)} would mean either dropping that
 * string or handing every join listener a parameter that is always {@code null}. One extra
 * parameter on its own interface is the smaller change, and it is the reason
 * {@link PlayerSessionEvents} keeps a third list rather than a third enum value.
 *
 * <h2>Backends only</h2>
 *
 * <p>Neither proxy has a death event at all — a proxy never sees the game state that produces one —
 * so on {@code GATEKEEPER} this simply never fires. That is not a gap to work around: the backends
 * behind the proxy run the same jar and report their own deaths, which is where the message is
 * authoritative anyway.
 *
 * <h2>Threading</h2>
 *
 * <p>Identical to {@link PlayerSessionListener}: invoked on {@code heimdall-io}, never on the
 * platform's event thread, with the timestamp taken at the event and carried across. A listener
 * that throws is contained and logged.
 */
public interface PlayerDeathListener {

    /**
     * @param player the player who died — still online, in the ordinary case, though they may have
     *     disconnected by the time this runs
     * @param deathMessage the server's own death message, exactly as it would be broadcast, or
     *     {@code null} when the server suppressed it. Never invented here: a relay that made one up
     *     would be attributing a sentence to the server that the server did not write
     * @param timestampMs when the platform observed the death, epoch millis
     */
    void onPlayerDeath(PlayerHandle player, String deathMessage, long timestampMs);
}
