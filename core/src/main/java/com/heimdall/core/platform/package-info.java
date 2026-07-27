/**
 * The seam between core and whichever server it is running on.
 *
 * <p>Core, the public API and the feature modules are platform-free, and the conformance module
 * fails the build if a Bukkit or Velocity type reaches any of them. That leaves a small number of
 * questions core genuinely cannot answer for itself, and this package is the whole of it:
 * {@link com.heimdall.core.platform.PlatformFacade} for the questions themselves, and a focused
 * interface per area — {@link com.heimdall.core.platform.PlayerDirectory},
 * {@link com.heimdall.core.platform.SchedulerBridge},
 * {@link com.heimdall.core.platform.ConsoleBridge},
 * {@link com.heimdall.core.platform.Integrations} — so a module depends on the one it uses rather
 * than on all of them.
 *
 * <p>{@link com.heimdall.core.platform.InstanceRoleDetector} is the odd one out: the platform
 * supplies two booleans, and the <em>policy</em> that turns them into a
 * {@link com.heimdall.core.config.ServerRole} lives here so it is identical everywhere and testable
 * without a server.
 *
 * <p>Implementations live in {@code :platform-bukkit}, {@code :platform-velocity} and the shared
 * {@code :platform-common}.
 */
package com.heimdall.core.platform;
