/**
 * The BungeeCord proxy binding — the third platform, and the second gatekeeper.
 *
 * <p>Loaded only by a BungeeCord (or Waterfall) proxy; neither the Bukkit nor the Velocity side of
 * the same jar ever links these classes.
 *
 * <p>Structurally it is the Velocity binding: the role is always
 * {@link com.heimdall.core.config.ServerRole#GATEKEEPER}, there is no chat listener because a proxy
 * cannot cancel signed chat, and the admin verb is {@code /hdp}. What differs is entirely BungeeCord's
 * own shape, and it is four things:
 *
 * <ul>
 *   <li><strong>The login gate is asynchronous by contract, not by convenience.</strong> Velocity's
 *       {@code LoginEvent} handler may block; BungeeCord's fires on the connection's netty event
 *       loop, so the whitelist decision is deferred with
 *       {@code registerIntent}/{@code completeIntent} and runs on {@code heimdall-io}. An intent that
 *       is never completed hangs that player's connection forever, with no timeout anywhere to
 *       rescue it, which is why {@link com.heimdall.platform.bungee.BungeeLoginListener} is the most
 *       carefully tested class in this package. Departure D75.
 *   <li><strong>There is no Adventure anywhere near it.</strong> BungeeCord's text API is its own
 *       {@code BaseComponent}, so — unlike Velocity, where the server's Adventure and Heimdall's
 *       relocated copy collide (departure D44) — nothing here needs reflection. Heimdall's component
 *       becomes §-coded legacy text and BungeeCord parses that. Departure D76.
 *   <li><strong>The console is {@code java.util.logging}.</strong> BungeeCord runs no log4j at any
 *       version, so the tap is {@link com.heimdall.platform.common.JulConsoleTap}, attached to the
 *       proxy's own logger rather than to the JUL root. Departure D77.
 *   <li><strong>There is nothing to migrate.</strong> v2 shipped Bukkit and Velocity builds and
 *       nothing else, so no Bungee proxy has ever had a v2 config directory. Departure D78.
 * </ul>
 */
package com.heimdall.platform.bungee;
