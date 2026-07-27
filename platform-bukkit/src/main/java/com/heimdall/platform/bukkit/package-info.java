/**
 * The Bukkit/Spigot platform binding, compiled against the Spigot 1.8.8 API.
 *
 * <p>This is one of the few packages allowed to touch {@code org.bukkit}; core, api and the feature
 * modules are not, and the {@code :conformance} module fails the build if that ever stops being
 * true.
 *
 * <p>{@code HeimdallBukkitPlugin} is a shell; {@code BukkitBootstrap} owns the order things are
 * built in; everything that is not Bukkit-specific lives in
 * {@code com.heimdall.core.wiring.HeimdallRuntime} and is shared with Velocity. The
 * {@code adapter} sub-package holds the pieces that differ across the 1.8.8-to-1.21 range and the
 * probes that choose between them.
 */
package com.heimdall.platform.bukkit;
