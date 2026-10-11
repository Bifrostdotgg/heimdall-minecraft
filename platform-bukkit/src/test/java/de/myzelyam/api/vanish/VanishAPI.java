package de.myzelyam.api.vanish;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/** Stand-in for PremiumVanish's Bukkit API; the test steers who is invisible. */
public final class VanishAPI {

    /** Online and offline invisible players, as PremiumVanish would hold them. */
    public static final Set<UUID> INVISIBLE =
            Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

    /** How many times {@link #isInvisibleOffline} was asked. */
    public static volatile int offlineChecks;

    private VanishAPI() {
    }

    /** Modelled on MySQL mode, the case PremiumVanish documents: false once the player is offline. */
    public static boolean isInvisible(Player p) {
        return p.isOnline() && INVISIBLE.contains(p.getUniqueId());
    }

    public static boolean isInvisibleOffline(UUID uuid) {
        offlineChecks++;
        return INVISIBLE.contains(uuid);
    }
}
