package com.heimdall.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.FakeShellContext;
import com.heimdall.core.util.Registration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A core generation's registrations, tracked locally (closed first, in order, by the bootstrap)
 * and against the shell (closed after {@code stop()} if the core forgot). Departure D87.
 */
class CoreRegistrationsTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final List<String> closed = new ArrayList<String>();

    private Registration named(final String name) {
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                closed.add(name);
            }
        });
    }

    @Test
    @DisplayName("tracked twice, closed once: the core's own teardown empties the shell's list too")
    void trackedOnBothSides(@TempDir Path dir) {
        FakeShellContext shell = new FakeShellContext("bukkit", dir);
        CoreRegistrations registrations = new CoreRegistrations(shell);
        registrations.track(named("listener"));
        registrations.track(named("timer"));
        assertEquals(2, shell.open());

        registrations.closeAll(logger);

        assertEquals(Arrays.asList("timer", "listener"), closed);
        assertEquals(0, shell.open(), "nothing left for the shell to find");
        assertEquals(0, shell.retire());
    }

    @Test
    @DisplayName("what the core forgets, the shell closes")
    void shellIsTheBackstop(@TempDir Path dir) {
        FakeShellContext shell = new FakeShellContext("bukkit", dir);
        CoreRegistrations registrations = new CoreRegistrations(shell);
        registrations.track(named("forgotten"));

        assertEquals(1, shell.retire());
        assertEquals(Arrays.asList("forgotten"), closed);
        registrations.closeAll(logger);
        assertEquals(Arrays.asList("forgotten"), closed, "and never twice");
    }

    @Test
    @DisplayName("a one-shot task stops being tracked once it has run")
    void oneShotUntracksWhenItRuns() {
        CoreRegistrations registrations = CoreRegistrations.untracked();
        final List<Runnable> scheduled = new ArrayList<Runnable>();
        final List<String> ran = new ArrayList<String>();

        registrations.oneShot(new Function<Runnable, Registration>() {
            @Override
            public Registration apply(Runnable wrapped) {
                scheduled.add(wrapped);
                return named("cancelled");
            }
        }, new Runnable() {
            @Override
            public void run() {
                ran.add("kick");
            }
        });
        assertEquals(1, registrations.size());

        scheduled.get(0).run();

        assertEquals(Arrays.asList("kick"), ran);
        assertEquals(0, registrations.size(), "a timer that fired must not pile up on the stack");
        assertTrue(closed.isEmpty(), "and its cancel must not run after it has fired");
    }

    @Test
    @DisplayName("a pending one-shot is cancelled by the teardown, so it never fires into a stopped core")
    void pendingOneShotIsCancelled() {
        CoreRegistrations registrations = CoreRegistrations.untracked();

        registrations.oneShot(new Function<Runnable, Registration>() {
            @Override
            public Registration apply(Runnable wrapped) {
                return named("cancelled");
            }
        }, new Runnable() {
            @Override
            public void run() {
            }
        });
        registrations.closeAll(logger);

        assertEquals(Arrays.asList("cancelled"), closed);
    }

    @Test
    @DisplayName("a task the scheduler refused tracks nothing")
    void refusedScheduleTracksNothing() {
        CoreRegistrations registrations = CoreRegistrations.untracked();

        Registration handle = registrations.oneShot(new Function<Runnable, Registration>() {
            @Override
            public Registration apply(Runnable wrapped) {
                return Registration.NONE;
            }
        }, new Runnable() {
            @Override
            public void run() {
            }
        });

        assertSame(Registration.NONE, handle);
        assertEquals(0, registrations.size());
    }

    @Test
    @DisplayName("after closeAll, anything tracked is closed on arrival")
    void closedForGood() {
        CoreRegistrations registrations = CoreRegistrations.untracked();
        registrations.closeAll(logger);

        registrations.track(named("late"));

        assertEquals(Arrays.asList("late"), closed);
    }
}
