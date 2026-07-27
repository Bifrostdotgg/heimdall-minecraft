/**
 * The Console feature module — console log streaming and remote command execution.
 *
 * <p>Platform-free by contract: everything here talks to the server through core abstractions,
 * never through Bukkit or Velocity types directly. The {@code :conformance} module enforces it.
 */
package com.heimdall.module.console;
