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
 *   <li><strong>A consumer must not attach or detach a tap from inside itself.</strong> The
 *       implementation's attach and detach are serialised against each other; a consumer that
 *       called one of them while another thread was part-way through the other would be waiting
 *       for a lock held by something that is waiting for the delivery this consumer is in the
 *       middle of. Detaching in response to a line is a legitimate thing to want — a module
 *       switching itself off — and the way to do it is to hand the work to another thread.
 * </ul>
 *
 * <p>Implementations are safe to call from any thread.
 */
public interface ConsoleBridge {

    /**
     * Runs {@code command} as the server console.
     *
     * <p>The command is dispatched on whatever thread the platform requires, so the caller does not
     * have to know.
     *
     * <p><strong>A command the server does not have fails with
     * {@link UnknownCommandException}</strong>, rather than completing with an acknowledgement. That
     * is a deliberate correction: "the command ran and printed an error" is true of the transport
     * and false of the only caller that matters. {@code /offend} hands the server a punishment the
     * bot has already recorded an infraction for, so a missing punishment plugin means the record
     * exists and nothing happened — and the moderator standing there is the only person who can
     * reconcile that. Both platforms already return a boolean saying so; neither used to read it.
     *
     * @param command the command line, without a leading slash
     * @return a short acknowledgement, never the command's output
     * @throws UnknownCommandException completed exceptionally when no such command exists
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

    /**
     * How many tap consumers have been unsubscribed for throwing, since this server started.
     *
     * <p>A diagnostic rather than a feature, and it is here because there is nowhere else it could
     * be observed from. A consumer that throws is dropped <em>silently</em>, and necessarily so: the
     * drop happens inside the delivery guard, where a log line would be captured and fed back into
     * the loop the guard exists to break. So the implementation counts instead, and something
     * outside the capture path — {@code /hd status} — reports the number.
     *
     * <p>Without it, "the dashboard's console went quiet" and "a plugin's tap threw once and was
     * unsubscribed" are indistinguishable from every angle an operator has.
     *
     * <p>Defaults to zero, for a platform whose console cannot be tapped at all: no taps means no
     * drops, which is the honest answer rather than a stub.
     */
    default int droppedTapConsumers() {
        return 0;
    }
}
