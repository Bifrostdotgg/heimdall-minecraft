package com.heimdall.shell.contract;

/**
 * One generation of Heimdall's swappable half.
 *
 * <p>Found through {@link ShellContract#SERVICE_ENTRY} in the core jar and constructed with its
 * public no-argument constructor, once per load. An instance is started at most once and stopped at
 * most once; a rollback or a later swap always gets a fresh instance from a fresh classloader rather
 * than restarting this one, so a core never has to make its static state survive a second start.
 *
 * <h2>Threading</h2>
 *
 * <p>At server start both calls arrive on the thread the platform enables plugins on. During a swap
 * they arrive on the shell's own swap thread, never on a server thread: tearing a core down can wait
 * several seconds for its executors to drain, and a server thread blocked that long is a watchdog
 * crash. A core that needs the main thread for something must hop there itself.
 */
public interface HeimdallCore {

    /**
     * The contract this core was compiled against.
     *
     * <p>Implementations return {@link ShellContract#VERSION}, which javac inlines, so the value is
     * the one in force when the core was built. The shell refuses to start a core whose answer
     * differs from its own.
     */
    int contractVersion();

    /**
     * Builds and starts everything.
     *
     * <p>Throwing means "this generation could not start". The shell then calls {@link #stop()} to
     * unwind whatever was half-built, closes anything still tracked against the context, and either
     * rolls back to the previous core or, at server start, runs with no core and refuses logins.
     *
     * @param context this generation's view of the shell; never {@code null}
     */
    void start(ShellContext context) throws Exception;

    /**
     * Tears everything down. Called exactly once, after a successful or a failed {@link #start}.
     *
     * <p>Must not throw, and must not leave anything registered against the platform: whatever this
     * generation registered outlives it otherwise, holding its classloader and running its code after
     * the shell has moved on. {@link ShellContext#isSwapping()} says whether a successor is about to
     * start (hand state over) or the server is stopping (just stop).
     */
    void stop();
}
