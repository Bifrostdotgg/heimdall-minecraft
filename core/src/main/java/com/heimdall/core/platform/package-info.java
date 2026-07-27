/**
 * The seam between core and whichever server it is running on.
 *
 * <p>Core, the public API and the feature modules are platform-free, and the conformance module
 * fails the build if a Bukkit or Velocity type reaches any of them. That leaves a small number of
 * questions core genuinely cannot answer for itself — what role am I, where is my data directory,
 * how do I get onto the main thread — and {@link com.heimdall.core.platform.PlatformFacade} is the
 * whole of it.
 *
 * <p>Its implementations arrive with the platform adapters in phase 1c.
 */
package com.heimdall.core.platform;
