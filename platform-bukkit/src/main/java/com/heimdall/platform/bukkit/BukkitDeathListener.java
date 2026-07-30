package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.session.PlayerSessionEvents;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Turns Bukkit's player death into core's death notification.
 *
 * <h2>Why {@code MONITOR}, and why the message is read there</h2>
 *
 * <p>Same reason as {@link BukkitSessionListener}: nothing here changes the event, and {@code
 * MONITOR} is Bukkit's own name for a read-only observation of something that has already happened.
 *
 * <p>It matters more here than it does for a join, though, because the thing being read is
 * <em>mutable</em>. {@code PlayerDeathEvent.setDeathMessage} is how death-message plugins do their
 * work, and every one of them runs at a priority below {@code MONITOR}. Reading at {@code NORMAL}
 * would relay a sentence the server never broadcast — the vanilla one, on a server that had
 * deliberately replaced it. Reading last is what makes "the vanilla death message" actually mean
 * "the message this server showed".
 *
 * <h2>{@code null} is a real answer</h2>
 *
 * <p>{@code getDeathMessage()} is genuinely nullable: a plugin that suppresses the broadcast sets it
 * to {@code null}, and so does {@code /gamerule showDeathMessages false}. It is passed through as
 * {@code null} rather than substituted, because a server that chose not to announce a death has
 * chosen that, and inventing a sentence for it would relay something nobody wrote. What the bridge
 * does with an absent detail is the bridge's decision, not this class's.
 *
 * <h2>No proxy equivalent, and that is not an omission</h2>
 *
 * <p>Neither Velocity nor BungeeCord has a death event — a proxy does not see the game state that
 * produces one — so this listener exists on the Bukkit family alone. The backends behind a proxy run
 * the same jar and report their own deaths, which is where the message is authoritative anyway. See
 * departure D80.
 *
 * <h2>Nothing here blocks the tick loop</h2>
 *
 * <p>{@code PlayerDeathEvent} is on the main server thread. {@link PlayerSessionEvents} hands off to
 * {@code heimdall-io} and returns, so what runs on the tick loop is building a handle and queueing a
 * task.
 *
 * <p>The handler is total: an exception escaping a Bukkit listener is logged by the server as a
 * plugin fault, and nothing here is worth failing a death over.
 */
final class BukkitDeathListener implements Listener {

    private final HeimdallLogger logger;
    private final PlayerSessionEvents sessions;
    private final BukkitPlayerDirectory players;

    BukkitDeathListener(
            HeimdallLogger logger, PlayerSessionEvents sessions, BukkitPlayerDirectory players) {
        this.logger = logger;
        this.sessions = sessions;
        this.players = players;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        long observedAt = System.currentTimeMillis();
        Player player = event.getEntity();
        if (player == null) {
            return;
        }
        try {
            sessions.death(players.wrap(player), event.getDeathMessage(), observedAt);
        } catch (Throwable broken) {
            // Throwable, not RuntimeException, for the same reason every other binding in this
            // package says so: what escapes an API that moved between server versions is an Error,
            // and it would sail past a RuntimeException catch into Bukkit's event machinery.
            logger.error("could not report the death of " + player.getName(), broken);
        }
    }
}
