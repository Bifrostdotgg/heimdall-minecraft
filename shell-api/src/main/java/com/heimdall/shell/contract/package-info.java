/**
 * The hot-swap contract: what the permanent shell hands a core, and what a core hands back.
 *
 * <p>Everything here is loaded once, by the platform, from the shell jar. A core is loaded later in
 * a child classloader whose parent is the shell's, and it finds these types through that parent, so
 * both sides are always talking about the same {@code Class} objects. That is also why this package
 * is small and platform-free: a type in it can only change with a full server restart, and the
 * {@link com.heimdall.shell.contract.ShellContract#VERSION} both sides declare is how a release that
 * changed it is told apart from one that can be swapped in live.
 *
 * <p>Platform objects cross the boundary as {@link java.lang.Object} on purpose. The server's own
 * classes (a {@code JavaPlugin}, a Velocity {@code ProxyServer}) are loaded by the server, so the
 * shell and every core see the same ones; typing them here would only drag three platform APIs into
 * a module that the conformance suite holds to being platform-free. See departure D87.
 */
package com.heimdall.shell.contract;
