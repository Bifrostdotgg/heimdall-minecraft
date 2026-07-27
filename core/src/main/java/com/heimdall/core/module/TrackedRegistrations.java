package com.heimdall.core.module;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.Registration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Everything one module registered, so all of it can be undone without knowing what any of it was.
 *
 * <p>The mechanism behind {@link ModuleContext}'s promise. Handles are unwound in <strong>reverse
 * order</strong>, which is the same reason a stack unwinds that way: a module that opened a mirror
 * and then scheduled a task that writes to it must have the task cancelled before the mirror is
 * closed, or the last tick writes to something already shut.
 *
 * <p>Closing is idempotent and a handle that throws does not stop the rest — teardown is exactly
 * when a failure is least affordable, because whatever is left registered belongs to a module the
 * plugin has already stopped thinking about.
 *
 * <p>Thread-safe.
 */
final class TrackedRegistrations {

    private final HeimdallLogger logger;
    private final String moduleId;
    private final Deque<Registration> handles = new ArrayDeque<Registration>();
    private boolean closed;

    TrackedRegistrations(HeimdallLogger logger, String moduleId) {
        this.logger = logger;
        this.moduleId = moduleId;
    }

    /**
     * Records a handle and returns it unchanged.
     *
     * <p>Returning it matters: a module that wants to unregister something early still can, and
     * because {@link Registration} is idempotent, the later bulk close is a no-op for it.
     */
    Registration track(Registration registration) {
        if (registration == null) {
            return Registration.NONE;
        }
        boolean alreadyClosed;
        synchronized (handles) {
            alreadyClosed = closed;
            if (!alreadyClosed) {
                handles.push(registration);
            }
        }
        if (alreadyClosed) {
            // Registering through a context after the module was disabled. Undo it immediately
            // rather than leaking it, and say so — this is a module holding its context past its
            // own lifetime, which is a bug worth surfacing while it is still cheap to find.
            logger.warn("module '" + moduleId + "' registered something after it was disabled; "
                    + "undoing it. Its ModuleContext is only valid while the module is enabled.");
            registration.close();
        }
        return registration;
    }

    /** How many handles are outstanding. For tests and diagnostics. */
    int size() {
        synchronized (handles) {
            return handles.size();
        }
    }

    /**
     * Unwinds everything, most recent first.
     *
     * <p>Safe to call twice; the second call has nothing to do. After this the bag is <em>reopened</em>
     * by {@link #reopen()} rather than implicitly, so a context cannot be revived by accident.
     */
    void closeAll() {
        List<Registration> toClose;
        synchronized (handles) {
            closed = true;
            toClose = new ArrayList<Registration>(handles);
            handles.clear();
        }
        for (Registration registration : toClose) {
            try {
                registration.close();
            } catch (RuntimeException e) {
                logger.error("unwinding a registration from module '" + moduleId + "' failed", e);
            }
        }
    }

    /** Reopens the bag for a fresh enable. */
    void reopen() {
        synchronized (handles) {
            handles.clear();
            closed = false;
        }
    }
}
