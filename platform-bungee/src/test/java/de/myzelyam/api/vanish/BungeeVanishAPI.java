package de.myzelyam.api.vanish;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.md_5.bungee.api.connection.ProxiedPlayer;

/**
 * Test-only stand-in for PremiumVanish's BungeeCord API (PremiumVanishAPI, MIT), with the same
 * name and signatures; the test steers who is invisible. The two event classes it goes with are
 * the {@code vanishApi} source set's compile-time stand-ins. Never shipped.
 */
public final class BungeeVanishAPI {

    /** Players PremiumVanish holds as vanished, online or not. */
    public static final Set<UUID> INVISIBLE =
            Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

    private BungeeVanishAPI() {
    }

    public static boolean isInvisible(ProxiedPlayer p) {
        return INVISIBLE.contains(p.getUniqueId());
    }

    public static List<UUID> getAllInvisiblePlayers() {
        return new ArrayList<UUID>(INVISIBLE);
    }
}
