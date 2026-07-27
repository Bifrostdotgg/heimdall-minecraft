package com.heimdall.core.platform;

import com.heimdall.core.util.Registration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Running a command as the console, and watching what the console prints.
 *
 * <h2>The acknowledgement is not the output</h2>
 *
 * <p>{@link #dispatchCommand} completes with a short human-readable acknowledgement — "dispatched
 * {@code say hi}" — and <strong>not</strong> with whatever the command printed. Neither platform
 * offers a way to attribute console output back to the command that caused it: the server writes to
 * one log, asynchronously, interleaved with everything else it is doing. v2 claimed the same thing
 * and returned the same acknowledgement; the difference is that this one says so in its signature.
 *
 * <p>The dashboard's console view gets the real output from {@link #attachLogTap} instead, which is
 * the honest shape of the feature: run the command, then watch the console.
 *
 * <h2>The tap must never be able to take a server down</h2>
 *
 * <p>A tap consumer is invoked from inside the server's own logging pipeline, on whatever thread
 * emitted the line. Three rules follow, and they are not advisory:
 *
 * <ul>
 *   <li><strong>A consumer must not log.</strong> Logging from inside the tap re-enters the logging
 *       framework, which calls the tap again. Implementations contain that, but a consumer that
 *       relies on being contained is a consumer that produces a stack overflow the day the
 *       containment is refactored.
 *   <li><strong>A consumer must be cheap and must not block.</strong> It is on the critical path of
 *       every log line the server writes.
 *   <li><strong>A consumer must not throw.</strong> Implementations swallow what does escape, but
 *       a broken tap silently dropping half the console is worse than one that never threw.
 * </ul>
 *
 * <p>Implementations are safe to call from any thread.
 */
public interface ConsoleBridge {

    /**
     * Runs {@code command} as the server console.
     *
     * <p>The command is dispatched on whatever thread the platform requires, so the caller does not
     * have to know. The future completes with the acknowledgement once dispatch has been
     * <em>attempted</em>, and fails only if the platform refused outright — a command that does not
     * exist is a successful dispatch that printed an error, which the tap will carry.
     *
     * @param command the command line, without a leading slash
     * @return a short acknowledgement, never the command's output
     */
    CompletableFuture<String> dispatchCommand(String command);

    /**
     * Subscribes to every line the server's console emits.
     *
     * <p>Lines arrive already ANSI-stripped and filtered to {@code INFO} and above — the volume
     * below that is a debugging tool, not something to ship over a WebSocket. Delivery is
     * best-effort: an implementation that cannot keep up drops the oldest lines rather than growing
     * a queue without bound or slowing the server down.
     *
     * <p>A platform with no attachable logging framework returns {@link Registration#NONE} and
     * delivers nothing, which is a degraded console rather than a failed boot.
     *
     * @return a handle that unsubscribes; closing it twice is a no-op
     */
    Registration attachLogTap(Consumer<LogLine> consumer);
}
