package com.heimdall.platform.bungee;

import de.myzelyam.api.vanish.BungeePlayerHideEvent;
import de.myzelyam.api.vanish.BungeePlayerShowEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

/**
 * PremiumVanish's hide and show events on BungeeCord. Departure D88.
 *
 * <p>The one class in the vanish integration that names a PremiumVanish type at compile time, and it
 * has to: BungeeCord's event bus finds handlers by the declared parameter type of an
 * {@code @EventHandler} method and dispatches on the event's exact class, so there is no way to
 * register for a class known only by name. It is compiled against stand-ins under the real names
 * (the {@code vanishApi} source set, never shipped) and is <strong>only ever loaded after
 * PremiumVanish has been found</strong>: {@link BungeeVanish} touches it nowhere else, so on a proxy
 * without PremiumVanish nothing tries to resolve those names at all.
 *
 * <p>{@code HIGHEST}, BungeeCord's last priority: nothing here changes the event.
 *
 * <p><strong>Public, and it has to be.</strong> BungeeCord's {@code EventHandlerMethod} calls
 * {@code Method.invoke} without {@code setAccessible}, so a handler declared on a package-private
 * class is refused with {@code IllegalAccessException}, which its event bus rethrows as an
 * {@code Error} out of {@code post()}. {@code BungeeVanishTest} dispatches through the real bus to pin
 * this. (The Bukkit rule is the opposite, and Bukkit-only: D87's package-private listeners exist for
 * Paper's executor cache, which BungeeCord does not have.)
 */
public final class BungeeVanishListener implements Listener {

    private final BungeeVanish owner;

    BungeeVanishListener(BungeeVanish owner) {
        this.owner = owner;
    }

    /**
     * Whether the event classes this listener was linked against are PremiumVanish's own.
     *
     * <p>They are unless some other plugin wrongly shaded PremiumVanish's API (its classes complain
     * loudly when that happens). A listener linked to a copy would register fine and never hear a
     * thing, so the caller checks first and says so instead.
     */
    static boolean linksTo(ClassLoader vanishLoader) throws ClassNotFoundException {
        return Class.forName(BungeeVanish.HIDE_EVENT, false, vanishLoader)
                        == BungeePlayerHideEvent.class
                && Class.forName(BungeeVanish.SHOW_EVENT, false, vanishLoader)
                        == BungeePlayerShowEvent.class;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHide(BungeePlayerHideEvent event) {
        owner.onEvent(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onShow(BungeePlayerShowEvent event) {
        owner.onEvent(event.getPlayer(), false);
    }
}
