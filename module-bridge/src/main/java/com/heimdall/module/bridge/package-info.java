/**
 * The Bridge feature module — Minecraft chat and player events out to Discord, Discord messages
 * back in.
 *
 * <p><strong>Relay only, forever.</strong> Nothing in this package stores a message. What it holds
 * is one bounded, drop-oldest queue per frame family, drained once a second and discarded whether or
 * not there is a bot to send it to; nothing else in here keeps a reference to a chat line after the
 * flush that shipped it. Log lines carry counts and lengths, never content — see
 * {@link com.heimdall.module.bridge.HeimdallBridgeModule} and departure D79.
 *
 * <p>Platform-free by contract: everything here talks to the server through core abstractions,
 * never through Bukkit or Velocity types directly. The {@code :conformance} module enforces it.
 */
package com.heimdall.module.bridge;
