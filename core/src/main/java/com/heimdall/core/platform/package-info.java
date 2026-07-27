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
 *
 * <h2>What is deliberately not here: join and quit</h2>
 *
 * <p>Nothing in this package carries a player joining or leaving, and phase 1c registers no listener
 * for either. The shape is decided rather than open, and phase 1d builds it — see
 * {@code docs/v2-departures.md} § "Seams named but not built".
 *
 * <p>They arrive as <strong>notifications</strong>, through a {@code PlayerSessionEvents}
 * dispatcher: a platform adapter pushes a {@link com.heimdall.core.platform.PlayerHandle} and a
 * timestamp, and modules subscribe through {@code ModuleContext} so the registration is tracked and
 * unwound with the module like every other one.
 *
 * <p>Not a third {@code Pipeline} — a pipeline arbitrates a decision, and there is none to arbitrate
 * once a player is already in or already gone. Not more methods on
 * {@link com.heimdall.core.platform.PlatformFacade} either: the facade is core asking the platform a
 * question, and this is the platform telling core something happened.
 *
 * <p>1c ships nothing rather than dead listeners. The first real consumer is the whitelist mirror's
 * join/quit window in 1d, and a listener with no consumer is one nobody notices has stopped working.
 */
package com.heimdall.core.platform;
