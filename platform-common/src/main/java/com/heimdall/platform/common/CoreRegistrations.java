package com.heimdall.platform.common;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.Registration;
import com.heimdall.shell.contract.Registrations;
import com.heimdall.shell.contract.ShellContext;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Everything one core generation registers against its platform, tracked twice.
 *
 * <h2>Why twice</h2>
 *
 * <p>Each registration is pushed onto this generation's own LIFO stack <em>and</em> tracked against
 * the shell's context. The local stack is what the bootstrap closes first in {@code disable()}, so
 * listeners and timers come off the server before the runtime behind them is torn down, in the
 * reverse of the order they went on. The shell's copy is the backstop: whatever the core forgets,
 * or fails to undo because a step threw, the shell closes after {@code stop()} returns. Closing a
 * handle through either side closes it once and removes it from both.
 *
 * <p>Before the hot-swap split nothing needed this: a plugin disable unregistered everything the
 * plugin had registered. A swap is not a plugin disable, so every registration a generation makes
 * has to be undone by the generation itself (departure D87).
 *
 * <p>Thread-safe. {@code context} may be {@code null} in tests, which then track locally only.
 */
public final class CoreRegistrations {

    private final ShellContext context;
    private final Registrations local = new Registrations();

    public CoreRegistrations(ShellContext context) {
        this.context = context;
    }

    /** A tracker with no shell behind it, for tests and for code that runs outside a core. */
    public static CoreRegistrations untracked() {
        return new CoreRegistrations(null);
    }

    /** The shell context, or {@code null} when there is none. */
    public ShellContext context() {
        return context;
    }

    /** Tracks {@code registration} here and against the shell; the handle closes it once. */
    public Registration track(Registration registration) {
        if (registration == null || registration == Registration.NONE) {
            return Registration.NONE;
        }
        return local.add(context == null ? registration : context.track(registration));
    }

    /** Tracks a handle the shell already tracks (a command or tunnel binding) on the local stack. */
    public Registration keep(Registration alreadyTracked) {
        return local.add(alreadyTracked);
    }

    /**
     * Schedules a one-shot task through {@code schedule} and tracks it until it either runs or is
     * cancelled, so a delayed task can never fire into a stopped generation, and a task that has
     * already run does not sit on the stack for the life of the generation.
     *
     * @param schedule schedules the runnable it is given and returns a handle that cancels it
     */
    public Registration oneShot(Function<Runnable, Registration> schedule, final Runnable task) {
        final AtomicBoolean fired = new AtomicBoolean();
        final AtomicReference<Registration> tracked = new AtomicReference<Registration>();
        final Registration cancel = schedule.apply(new Runnable() {
            @Override
            public void run() {
                fired.set(true);
                forget(tracked);
                task.run();
            }
        });
        if (cancel == null || cancel == Registration.NONE) {
            return Registration.NONE;
        }
        Registration handle = track(Registration.once(new Runnable() {
            @Override
            public void run() {
                // Cancelling a task that is already running (it is closing its own handle) would be
                // harmless on every platform, but there is no reason to ask.
                if (!fired.get()) {
                    cancel.close();
                }
            }
        }));
        tracked.set(handle);
        if (fired.get()) {
            // It ran before the handle existed; it already had nothing to forget.
            forget(tracked);
        }
        return handle;
    }

    private static void forget(AtomicReference<Registration> tracked) {
        Registration handle = tracked.getAndSet(null);
        if (handle != null) {
            handle.close();
        }
    }

    /**
     * Closes everything, newest first. Idempotent; anything tracked afterwards is closed on arrival.
     *
     * @return how many registrations were closed
     */
    public int closeAll(final HeimdallLogger logger) {
        return local.closeAll(new Registrations.FailureSink() {
            @Override
            public void failed(Throwable failure) {
                logger.error("undoing a platform registration failed; continuing", failure);
            }
        });
    }

    /** How many registrations are still open. */
    public int size() {
        return local.size();
    }
}
