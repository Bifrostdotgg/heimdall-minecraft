/**
 * Heimdall's own threads.
 *
 * <p>Every pool in the plugin is created here, named here, and shut down here. Nothing anywhere in
 * the build may reach the JVM-wide common {@code ForkJoinPool} — not through {@code
 * ForkJoinPool.commonPool()}, not through {@code parallelStream()}, and not through an
 * executor-less {@code CompletableFuture.*Async} overload — and the conformance module fails the
 * build if it does. The common pool is shared with the server's own parallel work and is sized by
 * core count, so on a small VPS a burst of logins starves it, and work stuck behind somebody else's
 * is undiagnosable from inside Heimdall.
 */
package com.heimdall.core.concurrent;
