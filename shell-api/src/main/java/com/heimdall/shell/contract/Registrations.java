package com.heimdall.shell.contract;

import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.List;

/**
 * A stack of {@link Registration}s that is torn down last-in first-out, exactly once.
 *
 * <p>The one teardown rule that the whole hot-swap design leans on: a generation that stops must
 * leave nothing registered behind it, whatever order it was built in and however badly part of the
 * teardown goes. Three properties make that hold, and each has a test:
 *
 * <ul>
 *   <li><strong>Reverse order.</strong> Things are built on top of each other (a listener on top of
 *       a pipeline on top of a pool), and undoing them in reverse is the only order in which each
 *       undo still finds what it depends on.
 *   <li><strong>It never stops early.</strong> Every close runs inside a {@code Throwable} catch.
 *       An {@code Error} from one step (a {@code NoSuchMethodError} out of a server API that moved,
 *       the failure class departures D43 to D45 are about) must not skip the steps after it, because
 *       the skipped ones are leaks.
 *   <li><strong>Idempotent, and closed for good.</strong> {@link #closeAll()} twice is a no-op, and
 *       anything {@link #add added} after it is closed on the spot: late work from a stopped
 *       generation must not be able to register itself again.
 * </ul>
 *
 * <p>A handle returned by {@link #add} closes its registration and removes it from the stack, so a
 * short-lived registration (a one-shot timer) does not accumulate here for the life of the
 * generation.
 *
 * <p>Thread-safe.
 */
public final class Registrations {

    /** Told about each close that threw. Never itself allowed to stop the teardown. */
    public interface FailureSink {

        void failed(Throwable failure);
    }

    private final Object lock = new Object();
    private final List<Entry> entries = new ArrayList<Entry>();
    private boolean closed;

    /**
     * Pushes {@code registration} and returns a handle that undoes it early.
     *
     * <p>Once {@link #closeAll()} has run, closes {@code registration} immediately and returns
     * {@link Registration#NONE}.
     */
    public Registration add(Registration registration) {
        if (registration == null || registration == Registration.NONE) {
            return Registration.NONE;
        }
        Entry entry = new Entry(registration);
        synchronized (lock) {
            if (!closed) {
                entries.add(entry);
                return entry;
            }
        }
        closeQuietly(registration, null);
        return Registration.NONE;
    }

    /**
     * Closes everything, newest first, and refuses anything added afterwards.
     *
     * @param sink told about each close that threw; may be {@code null}
     * @return how many registrations were closed by this call
     */
    public int closeAll(FailureSink sink) {
        List<Entry> snapshot;
        synchronized (lock) {
            if (closed) {
                return 0;
            }
            closed = true;
            snapshot = new ArrayList<Entry>(entries);
            entries.clear();
        }
        int count = 0;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            if (snapshot.get(i).closeTracked(sink)) {
                count++;
            }
        }
        return count;
    }

    /** {@link #closeAll(FailureSink)} with failures dropped. */
    public int closeAll() {
        return closeAll(null);
    }

    /** Whether {@link #closeAll} has run. */
    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /** How many registrations are still open. */
    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    private void forget(Entry entry) {
        synchronized (lock) {
            entries.remove(entry);
        }
    }

    private static void closeQuietly(Registration registration, FailureSink sink) {
        try {
            registration.close();
        } catch (Throwable failure) {
            if (sink != null) {
                try {
                    sink.failed(failure);
                } catch (Throwable ignored) {
                    // The sink is reporting, not deciding. A sink that throws must not be what stops
                    // the rest of the teardown.
                }
            }
        }
    }

    /** One tracked registration. Closes at most once whoever gets there first. */
    private final class Entry implements Registration {

        private final Registration registration;
        private boolean done;

        Entry(Registration registration) {
            this.registration = registration;
        }

        private synchronized boolean claim() {
            if (done) {
                return false;
            }
            done = true;
            return true;
        }

        /** The early close, through the handle. */
        @Override
        public void close() {
            if (!claim()) {
                return;
            }
            forget(this);
            registration.close();
        }

        boolean closeTracked(FailureSink sink) {
            if (!claim()) {
                return false;
            }
            closeQuietly(registration, sink);
            return true;
        }
    }
}
