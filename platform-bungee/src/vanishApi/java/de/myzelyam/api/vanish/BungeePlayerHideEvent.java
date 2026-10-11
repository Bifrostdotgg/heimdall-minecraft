package de.myzelyam.api.vanish;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Event;

/**
 * A compile-time stand-in for PremiumVanish's own class of this name, with the same package, name,
 * superclass and accessor (PremiumVanishAPI, MIT). Never shipped: this source set is
 * {@code compileOnly} for {@code :platform-bungee} and {@code verifyCoreJar} refuses any class
 * outside {@code com/heimdall/}. At runtime the name resolves to PremiumVanish's real class.
 *
 * <p>It exists because BungeeCord's event bus dispatches only to a handler method whose declared
 * parameter is the event class, so {@code BungeeVanishListener} has to name it. Departure D88.
 */
public class BungeePlayerHideEvent extends Event {

    private final ProxiedPlayer player;

    public BungeePlayerHideEvent(ProxiedPlayer player) {
        this.player = player;
    }

    /** The player who is vanishing. */
    public ProxiedPlayer getPlayer() {
        return player;
    }
}
