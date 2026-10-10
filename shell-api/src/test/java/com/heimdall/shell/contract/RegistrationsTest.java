package com.heimdall.shell.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The teardown rule the whole hot-swap design leans on: last in, first out, never stopping early,
 * exactly once, and closed for good afterwards (departure D87).
 */
class RegistrationsTest {

    private final List<String> closed = new ArrayList<String>();
    private final Registrations registrations = new Registrations();

    private Registration named(final String name) {
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                closed.add(name);
            }
        });
    }

    @Test
    @DisplayName("closes newest first, so each undo still finds what it was built on")
    void lastInFirstOut() {
        registrations.add(named("pool"));
        registrations.add(named("pipeline"));
        registrations.add(named("listener"));

        assertEquals(3, registrations.closeAll());
        assertEquals(Arrays.asList("listener", "pipeline", "pool"), closed);
    }

    @Test
    @DisplayName("an Error from one close does not skip the ones after it, and is reported")
    void neverStopsEarly() {
        final List<Throwable> failures = new ArrayList<Throwable>();
        registrations.add(named("first"));
        registrations.add(new Registration() {
            @Override
            public void close() {
                throw new NoSuchMethodError("an API that moved between server versions");
            }
        });
        registrations.add(named("last"));

        registrations.closeAll(new Registrations.FailureSink() {
            @Override
            public void failed(Throwable failure) {
                failures.add(failure);
            }
        });

        assertEquals(Arrays.asList("last", "first"), closed);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0) instanceof NoSuchMethodError);
    }

    @Test
    @DisplayName("closing twice closes nothing twice")
    void idempotent() {
        registrations.add(named("once"));
        assertEquals(1, registrations.closeAll());
        assertEquals(0, registrations.closeAll());
        assertEquals(Arrays.asList("once"), closed);
    }

    @Test
    @DisplayName("anything added after closeAll is closed on arrival and not kept")
    void closedForGood() {
        registrations.closeAll();

        Registration handle = registrations.add(named("late"));

        assertSame(Registration.NONE, handle);
        assertEquals(Arrays.asList("late"), closed,
                "late work from a stopped generation must not be able to register itself again");
        assertEquals(0, registrations.size());
    }

    @Test
    @DisplayName("an early close through the handle runs once and leaves the stack")
    void earlyCloseForgets() {
        Registration handle = registrations.add(named("timer"));
        registrations.add(named("listener"));

        handle.close();
        handle.close();
        assertEquals(1, registrations.size(), "a one-shot that ran must not accumulate");

        registrations.closeAll();
        assertEquals(Arrays.asList("timer", "listener"), closed);
    }

    @Test
    @DisplayName("a throwing sink cannot stop the teardown either")
    void throwingSinkIsContained() {
        registrations.add(named("first"));
        registrations.add(new Registration() {
            @Override
            public void close() {
                throw new IllegalStateException("broken");
            }
        });

        registrations.closeAll(new Registrations.FailureSink() {
            @Override
            public void failed(Throwable failure) {
                throw new IllegalStateException("the sink is broken too");
            }
        });

        assertEquals(Arrays.asList("first"), closed);
    }
}
