/**
 * The Velocity proxy binding — the only Java 17 module in the build.
 *
 * <p>Loaded only by the proxy; the Bukkit side of the same jar never links these classes.
 *
 * <p>Two things differ structurally from the Bukkit binding, and both come from what a proxy is
 * rather than from how Velocity's API is shaped:
 *
 * <ul>
 *   <li><strong>No main thread.</strong> Every Velocity API is safe from anywhere, so the platform
 *       executor runs tasks inline. That is a conforming implementation, not a degraded one — see
 *       {@link com.heimdall.core.platform.SchedulerBridge}.
 *   <li><strong>No chat interception — observation only.</strong> A proxy cannot cancel signed
 *       chat, so <em>blocking</em> a message belongs to the backend servers. The role system exists
 *       for exactly this division: the gatekeeper owns login, the enforcers own everything after
 *       it. {@link com.heimdall.platform.velocity.VelocityChatListener} reads chat for the Discord
 *       relay and touches nothing, which is legal in the signed-chat era in a way cancelling is
 *       not (departure D81).
 * </ul>
 *
 * <p>And one thing differs because of how the jar is built: Velocity's API speaks Adventure, and
 * Heimdall shades and relocates its own. {@link com.heimdall.platform.velocity.VelocityText} is the
 * only place those two meet, and the only place in the codebase that reflects in order to make what
 * would otherwise be a normal method call.
 */
package com.heimdall.platform.velocity;
