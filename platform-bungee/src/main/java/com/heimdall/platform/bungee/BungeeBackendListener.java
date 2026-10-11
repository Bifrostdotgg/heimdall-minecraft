package com.heimdall.platform.bungee;

import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

/**
 * A player's backend connections, which anchor a join's vanish wait on this proxy: PremiumVanish
 * here learns a player's state only once they have reached a backend. Registered only while
 * PremiumVanish is installed, and tracked with its other hooks. Departure D88.
 *
 * <p>Public, like every BungeeCord listener: BungeeCord invokes handlers without
 * {@code setAccessible}.
 */
public final class BungeeBackendListener implements Listener {

    private final BungeeVanish owner;

    BungeeBackendListener(BungeeVanish owner) {
        this.owner = owner;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConnected(ServerConnectedEvent event) {
        owner.onBackend(event.getPlayer());
    }
}
