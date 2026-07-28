/**
 * The self-updater: knowing this server is behind, and putting the new jar where a restart finds it.
 *
 * <p>v2's {@code UpdateChecker} plus the two copies of its install logic that lived in the Paper and
 * Velocity plugin classes. The split here is the platform boundary and nothing else: everything that
 * can be decided without a server type — version ordering, the download hardening, the
 * is-there-an-update state machine, the schedule — lives in this package, and the two things that
 * cannot are {@link com.heimdall.core.update.ReleaseSource} and
 * {@link com.heimdall.core.update.UpdateInstaller}.
 *
 * <p>The download rules are a value ({@link com.heimdall.core.update.DownloadPolicy}) rather than
 * constants inside the method that enforces them, because the URL comes from the bot over the
 * network and an allowlist nobody can test is an allowlist nobody has tested — which is what v2 had.
 *
 * <p>Nothing here applies an update to a running server. Every strategy stages a jar that the next
 * restart picks up.
 */
package com.heimdall.core.update;
