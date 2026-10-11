package de.myzelyam.api.vanish;

import com.velocitypowered.api.proxy.Player;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stand-in for PremiumVanish's Velocity API; the test steers who is invisible. */
public final class VelocityVanishAPI {

    /** Players PremiumVanish holds as vanished, online or not. */
    public static final Set<UUID> INVISIBLE =
            Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

    private VelocityVanishAPI() {
    }

    public static boolean isInvisible(Player player) {
        return INVISIBLE.contains(player.getUniqueId());
    }

    public static List<UUID> getAllInvisiblePlayers() {
        return new ArrayList<UUID>(INVISIBLE);
    }
}
