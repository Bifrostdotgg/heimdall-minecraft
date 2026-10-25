package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;

/**
 * Backend freeze and muted-sign cancel. Talks to the punishments module by reflection so this
 * platform module does not compile against a feature module.
 *
 * <p>A frozen player stays where they are and changes nothing: no walking (looking around is
 * allowed), no clicking blocks or entities, no breaking or placing, no dropping or picking up,
 * no moving items in an inventory. They cannot be hurt and cannot hurt anyone either, since they
 * cannot move out of the way. They cannot throw, shoot, eat, or ride. Teleports are left alone on
 * purpose, apart from the ones the player causes (an ender pearl, chorus fruit), so staff can still
 * bring a frozen player somewhere to talk.
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

    /**
     * Not {@code ignoreCancelled}: CraftBukkit fires a click on the air already cancelled (it has
     * no block to use), with the item in hand still allowed, so a filtered handler never saw an
     * ender pearl, a bow or food. Cancelling denies the item as well as the block.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Anything that fires a projectile without a click event to stop it first. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        Player shooter = attacker(event.getEntity());
        if (shooter != null && frozen(shooter)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * The teleports a player causes themselves: an ender pearl, or chorus fruit on servers that
     * have it (matched by name, since the 1.8 API this compiles against does not). Every other
     * teleport, staff bringing them somewhere included, is left alone.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause == null) return;
        String name = cause.name();
        if (!"ENDER_PEARL".equals(name) && !"CHORUS_FRUIT".equals(name)) return;
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** No getting on anything. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player && frozen((Player) event.getEntered())) {
            event.setCancelled(true);
        }
    }

    /**
     * Frozen while already riding: a passenger's movement arrives as the vehicle's, so no
     * {@code PlayerMoveEvent} fires to stop it. Put them on their feet, where the move handler
     * holds them.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    @SuppressWarnings("deprecation")
    public void onVehicleMove(VehicleMoveEvent event) {
        Entity passenger = event.getVehicle().getPassenger();
        if (passenger instanceof Player && frozen((Player) passenger)) {
            event.getVehicle().eject();
        }
    }

    /** Told to the module, so a frozen player kicked off this server is not banned for leaving. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        Object module = module();
        if (module == null) return;
        try {
            module.getClass().getMethod("noteKicked", UUID.class)
                    .invoke(module, event.getPlayer().getUniqueId());
        } catch (Throwable e) {
            logger.debug(() -> "punishment guard noteKicked failed: " + e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * Deprecated on modern servers in favour of {@code EntityPickupItemEvent}, which 1.8.8 does
     * not have, and still fired by them for a player.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player && frozen((Player) event.getWhoClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player && frozen((Player) event.getWhoClicked())) {
            event.setCancelled(true);
        }
    }

    /** A frozen player takes no damage: they cannot move out of the way of any. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && frozen((Player) event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** And deals none, by hand or by anything they shot before the freeze landed. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamaging(EntityDamageByEntityEvent event) {
        Player attacker = attacker(event.getDamager());
        if (attacker != null && frozen(attacker)) {
            event.setCancelled(true);
        }
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player) return (Player) damager;
        if (damager instanceof Projectile) {
            Object shooter = ((Projectile) damager).getShooter();
            if (shooter instanceof Player) return (Player) shooter;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        Player player = event.getPlayer();
        if (!muted(player)) return;
        event.setCancelled(true);
        // The notice names the player and the mute in one sentence, so a hidden mute has to narrow
        // its audience with it: told to every mute-node holder, it discloses exactly the fact the
        // mute is hidden to keep from them.
        notifyStaff("§e" + player.getName() + "§7 tried to edit a sign while muted.",
                muteHidden(player));
    }

    private boolean frozen(Player player) {
        return bool("isFrozen", player.getUniqueId());
    }

    private boolean muted(Player player) {
        return bool("isMuted", player.getUniqueId());
    }

    /**
     * Whether that mute is a hidden one.
     *
     * <p>Two failures, two answers, because they mean opposite things. A module with no such method
     * predates hidden punishments entirely, so nothing it holds can be hidden and the notice keeps
     * its usual audience. Any other failure is a module that does know about hidden and could not
     * answer, and there the safe default is the narrow audience: withholding one staff notice costs
     * a line, while sending it discloses a punishment.
     */
    private boolean muteHidden(Player player) {
        Object module = module();
        if (module == null) return false;
        Method m;
        try {
            m = module.getClass().getMethod("isMuteHidden", UUID.class);
        } catch (NoSuchMethodException tooOld) {
            return false;
        } catch (Throwable e) {
            logger.debug(() -> "punishment guard isMuteHidden lookup failed: " + e);
            return true;
        }
        try {
            return Boolean.TRUE.equals(m.invoke(module, player.getUniqueId()));
        } catch (Throwable e) {
            logger.debug(() -> "punishment guard isMuteHidden failed: " + e);
            return true;
        }
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

    /**
     * Sends the notice, on whichever shape of {@code notifyStaff} the loaded module has.
     *
     * <p>The fallback mirrors the reasoning in {@link #muteHidden} above: a module with no
     * two-argument overload predates hidden punishments entirely, so nothing it holds can be
     * hidden, {@code hiddenOnly} is false by construction, and the one-argument form is the same
     * call. Without the fallback the reflection simply threw and the notice was dropped, so a
     * stale module jar beside a current platform jar silently lost every muted-sign warning -
     * a failure with no symptom, which is the worst kind to ship.
     */
    private void notifyStaff(String line, boolean hiddenOnly) {
        Object module = module();
        if (module == null) return;
        try {
            module.getClass().getMethod("notifyStaff", String.class, boolean.class)
                    .invoke(module, line, Boolean.valueOf(hiddenOnly));
            return;
        } catch (NoSuchMethodException tooOld) {
            // Fall through to the one-argument form below.
        } catch (Throwable e) {
            logger.debug(() -> "punishment guard notify failed: " + e);
            return;
        }
        try {
            module.getClass().getMethod("notifyStaff", String.class).invoke(module, line);
        } catch (Throwable e) {
            logger.debug(() -> "punishment guard notify fallback failed: " + e);
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
