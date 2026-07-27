/**
 * Platform code that is not specific to a platform.
 *
 * <p>Three things ended up here, and they share one property: they are needed identically by
 * {@code :platform-bukkit} and {@code :platform-velocity}, and none of them can live in core.
 *
 * <ul>
 *   <li>{@link com.heimdall.platform.common.LuckPermsIntegration} — {@code net.luckperms:api} is
 *       genuinely platform-neutral, the same artifact on both families, so the group-diff logic is
 *       written once. v2 wrote it twice and the two copies diverged: the Velocity one checked
 *       whether a group existed before adding it and awaited the save, the Bukkit one did neither
 *       and cached a permanent "LuckPerms is missing" at construction (#796 / MC-10).
 *   <li>{@link com.heimdall.platform.common.Log4jConsoleTap} — both families run log4j2, so the
 *       console tap is one implementation.
 *   <li>{@link com.heimdall.platform.common.FloodgateIdentityProvider} and
 *       {@link com.heimdall.platform.common.TunnelSpiService} — pure reflection and pure
 *       delegation respectively, with no platform types in either.
 * </ul>
 *
 * <h2>Why not core</h2>
 *
 * <p>Two of these compile against a third-party API ({@code net.luckperms}, {@code org.apache.
 * logging.log4j}) and one is built on reflection. Core's contract is stronger than "no Bukkit
 * imports": it is code that depends on nothing but the JDK and Heimdall's own shaded libraries, so
 * that "what does core need at runtime" has a short and complete answer. Putting an optional plugin
 * API on core's compile classpath would blur that, and reflection is invisible to the conformance
 * rules — confining it to a platform module is the only way "core is platform-free" stays a
 * checkable claim rather than a habit (departure D9).
 *
 * <p>The conformance rules still cover this package: it is derived from the project graph, so
 * {@code noSharedParallelism} and {@code executorsAreAlwaysNamed} apply here exactly as they do
 * everywhere else. Only {@code platformIsolation}, which is scoped to
 * {@code com.heimdall.core/api/module}, does not — which is the point of the package living
 * under {@code com.heimdall.platform}.
 */
package com.heimdall.platform.common;
