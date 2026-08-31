package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Backend freeze (move/interact) and muted-sign cancel. Talks to the punishments module by
 * reflection so this platform module does not compile against a feature module.
 */
final class BukkitPunishmentGuard implements Listener {

    private final HeimdallLogger logger;

    BukkitPunishmentGuard(HeimdallLogger logger) {
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        if (frozen(event.getPlayer())) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        Player player = event.getPlayer();
        if (!muted(player)) return;
        event.setCancelled(true);
        notifyStaff("§e" + player.getName() + "§7 tried to edit a sign while muted.");
    }

    private boolean frozen(Player player) {
        return bool("isFrozen", player.getUniqueId());
    }

    private boolean muted(Player player) {
        return bool("isMuted", player.getUniqueId());
    }

    private boolean bool(String method, UUID uuid) {
        Object module = module();
        if (module == null) return false;
        try {
            Method m = module.getClass().getMethod(method, UUID.class);
            Object result = m.invoke(module, uuid);
            return Boolean.TRUE.equals(result);
        } catch (Throwable e) {
            logger.debug(() -> "punishment guard " + method + " failed: " + e);
            return false;
        }
    }

    private void notifyStaff(String line) {
        Object module = module();
        if (module == null) return;
        try {
            module.getClass().getMethod("notifyStaff", String.class).invoke(module, line);
        } catch (Throwable e) {
            logger.debug(() -> "punishment guard notify failed: " + e);
        }
    }

    private static Object module() {
        try {
            return Class.forName("com.heimdall.module.punishments.HeimdallPunishmentsModule")
                    .getField("INSTANCE")
                    .get(null);
        } catch (Throwable e) {
            return null;
        }
    }
}
