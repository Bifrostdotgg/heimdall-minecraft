package com.heimdall.platform.bungee;

import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

/**
 * A player's proxy logins and backend connections, which anchor a join's vanish wait on this proxy:
 * PremiumVanish here learns a player's state only once they have reached a backend. A login starts a
 * new session (forgetting the last one's connection) and runs at {@code LOWEST}, before the session
 * listener at {@code HIGHEST} reports the join. Registered only while
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

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPostLogin(PostLoginEvent event) {
        owner.onLogin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConnected(ServerConnectedEvent event) {
        owner.onBackend(event.getPlayer());
    }
}
