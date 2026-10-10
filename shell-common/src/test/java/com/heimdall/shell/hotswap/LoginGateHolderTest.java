package com.heimdall.shell.hotswap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.util.Registration;
import com.heimdall.shell.contract.LoginGate;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shell's half of the login gate: hold while a core is coming, refuse when none is, and never
 * admit a login no core has decided on (departure D87).
 */
class LoginGateHolderTest {

    private final RecordingShellLog log = new RecordingShellLog();

    private static final LoginGate GATE = new LoginGate() {
        @Override
        public void decide(Object event) {
        }
    };

    @Test
    @DisplayName("with a gate bound, a login gets it at once")
    void boundGateIsImmediate() {
        LoginGateHolder gates = new LoginGateHolder(log, 5_000L);
        gates.bind(GATE);
        gates.state(LoginGateHolder.State.RUNNING);

        assertSame(GATE, gates.await().gate());
    }

    @Test
    @DisplayName("mid-swap, a login holds and is handed the next core's gate when it binds")
    void holdsUntilTheNextGateBinds() throws Exception {
        final LoginGateHolder gates = new LoginGateHolder(log, 5_000L);
        gates.state(LoginGateHolder.State.SWAPPING);
        Thread binder = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(150L);
                } catch (InterruptedException ignored) {
                    return;
                }
                gates.bind(GATE);
            }
        });
        binder.start();

        long started = System.nanoTime();
        LoginGateHolder.Decision decision = gates.await();
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertSame(GATE, decision.gate());
        assertTrue(waited >= 100L && waited < 4_000L, "waited " + waited + "ms");
        binder.join();
    }

    @Test
    @DisplayName("a swap that outlasts the hold is refused as 'updating', never admitted")
    void holdThenDeny() {
        LoginGateHolder gates = new LoginGateHolder(log, 100L);
        gates.state(LoginGateHolder.State.SWAPPING);

        long started = System.nanoTime();
        LoginGateHolder.Decision decision = gates.await();

        assertNull(decision.gate());
        assertEquals(ShellMessages.LOGIN_UPDATING, decision.refusal());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 90L,
                "it must actually hold before refusing");
    }

    @Test
    @DisplayName("before the first core has started, logins hold the same way")
    void startingHolds() {
        LoginGateHolder gates = new LoginGateHolder(log, 50L);

        assertEquals(ShellMessages.LOGIN_UPDATING, gates.await().refusal());
    }

    @Test
    @DisplayName("with no core at all, a login is refused at once, and a waiting one is released")
    void noCoreDeniesAtOnce() throws Exception {
        final LoginGateHolder gates = new LoginGateHolder(log, 60_000L);
        gates.state(LoginGateHolder.State.SWAPPING);
        Thread failer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException ignored) {
                    return;
                }
                gates.state(LoginGateHolder.State.DOWN);
            }
        });
        failer.start();

        long started = System.nanoTime();
        LoginGateHolder.Decision decision = gates.await();

        assertNull(decision.gate());
        assertEquals(ShellMessages.LOGIN_NO_CORE, decision.refusal());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000L,
                "a login waiting on a swap that failed outright must not sit out the whole hold");
        assertEquals(ShellMessages.LOGIN_NO_CORE, gates.await().refusal(), "and later ones at once");
        failer.join();
    }

    @Test
    @DisplayName("a running core with no gate bound is a core bug: refused, and said once")
    void runningWithoutAGateFailsClosed() {
        LoginGateHolder gates = new LoginGateHolder(log, 5_000L);
        gates.state(LoginGateHolder.State.RUNNING);

        assertNull(gates.await().gate());
        assertNull(gates.await().gate());
        assertEquals(1, log.errors().size(), log.lines().toString());
    }

    @Test
    @DisplayName("an old generation's unbind does not clear its successor's gate")
    void unbindIsIdentityChecked() {
        LoginGateHolder gates = new LoginGateHolder(log, 5_000L);
        Registration old = gates.bind(GATE);
        LoginGate next = new LoginGate() {
            @Override
            public void decide(Object event) {
            }
        };
        gates.bind(next);

        old.close();

        assertSame(next, gates.current());
    }
}
