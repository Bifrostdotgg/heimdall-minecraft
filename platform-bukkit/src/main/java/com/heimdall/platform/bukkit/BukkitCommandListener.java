package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.pipeline.CommandAttempt;
import com.heimdall.core.pipeline.CommandPipeline;
import com.heimdall.core.pipeline.Verdict;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * Player-typed commands, into the command pipeline.
 *
 * <p>Bukkit family only. Proxies must not cancel commands: signed chat from 1.19.1 disconnects
 * the client if a proxy swallows the packet. Mute blocked-commands live on ENFORCER and STANDALONE.
 *
 * <p>Fail open: a throw from the pipeline must not freeze every command on the server.
 */
final class BukkitCommandListener implements Listener {

    private final HeimdallLogger logger;
    private final CommandPipeline pipeline;
    private final BukkitMessenger messenger;

    BukkitCommandListener(HeimdallLogger logger, CommandPipeline pipeline, BukkitMessenger messenger) {
        this.logger = logger;
        this.pipeline = pipeline;
        this.messenger = messenger;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player sender = event.getPlayer();
        if (sender == null) {
            return;
        }
        try {
            Verdict verdict = pipeline.dispatch(
                    CommandAttempt.of(sender.getUniqueId(), sender.getName(), event.getMessage()));
            if (verdict.isDeny()) {
                event.setCancelled(true);
                messenger.send(sender, verdict.reason());
            }
        } catch (Throwable broken) {
            if (event.isCancelled()) {
                logger.error("command from " + sender.getName() + " was blocked, but telling them why "
                        + "failed; they were cut off with no explanation", broken);
            } else {
                logger.error("the command pipeline threw; letting the command through", broken);
            }
        }
    }
}
