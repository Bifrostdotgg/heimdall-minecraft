package com.heimdall.shell.hotswap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.util.Registration;
import com.heimdall.shell.contract.LoginGate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** A gate that blocks in {@code decide} until {@link #release} is counted down. */
    private static final class BlockingGate implements LoginGate {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void decide(Object event) {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A gate that counts its calls and decides nothing. */
    private static final class CountingGate implements LoginGate {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void decide(Object event) {
            calls.incrementAndGet();
        }
    }

    private LoginGateHolder runningWith(LoginGate gate) {
        LoginGateHolder gates = new LoginGateHolder(log, 5_000L);
        gates.bind(gate);
        gates.state(LoginGateHolder.State.RUNNING);
        return gates;
    }

    @Test
    @DisplayName("a login is counted from the moment it is handed the gate, not when it starts deciding")
    void leaseCountsFromTheHandOut() {
        // The interleaving the review found, made deterministic: the login has been handed the old
        // core's gate, the swap suspends and drains before the login calls decide. The drain must
        // see it, and the late decision must not run on the old core at all.
        CountingGate old = new CountingGate();
        LoginGateHolder gates = runningWith(old);
        LoginGateHolder.Decision handedOut = gates.await();
        assertSame(old, handedOut.gate());

        gates.suspend();
        assertFalse(gates.drain(50L), "the drain must count a login that holds the gate");

        assertFalse(gates.decide(handedOut, "login"), "a voided lease's decision never stands");
        assertEquals(0, old.calls.get(), "the retired gate was asked anyway");
        assertTrue(gates.drain(0L));
    }

    @Test
    @DisplayName("the non-waiting lease is counted the same way")
    void nonWaitingLeaseIsCounted() {
        CountingGate old = new CountingGate();
        LoginGateHolder gates = runningWith(old);
        LoginGateHolder.Decision handedOut = gates.lease();
        assertSame(old, handedOut.gate());

        gates.suspend();

        assertFalse(gates.drain(50L));
        assertNull(gates.lease(), "nothing to lease while suspended");
        assertFalse(gates.decide(handedOut, "login"));
        assertEquals(0, old.calls.get());
    }

    @Test
    @DisplayName("drain waits for a login a gate is still deciding, and that decision stands")
    void drainWaitsForInFlight() throws Exception {
        final BlockingGate gate = new BlockingGate();
        final LoginGateHolder gates = runningWith(gate);
        final AtomicBoolean stood = new AtomicBoolean();
        Thread login = new Thread(() -> stood.set(gates.decide(gates.await(), "login")));
        login.start();
        assertTrue(gate.entered.await(2, TimeUnit.SECONDS));
        gates.suspend();
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(150L);
            } catch (InterruptedException ignored) {
                return;
            }
            gate.release.countDown();
        });
        releaser.start();

        long started = System.nanoTime();
        assertTrue(gates.drain(5_000L));
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(waited >= 100L, "drain returned while a login was in flight: " + waited + "ms");
        login.join();
        releaser.join();
        assertTrue(stood.get(), "finished before the drain gave up, so the old core's word stands");
    }

    @Test
    @DisplayName("a decision that outlives the drain does not stand, whatever the gate decided")
    void decisionOutlivingTheDrainIsVoided() throws Exception {
        final BlockingGate gate = new BlockingGate();
        final LoginGateHolder gates = runningWith(gate);
        final AtomicBoolean stood = new AtomicBoolean(true);
        Thread login = new Thread(() -> stood.set(gates.decide(gates.await(), "login")));
        login.start();
        assertTrue(gate.entered.await(2, TimeUnit.SECONDS));
        gates.suspend();

        assertFalse(gates.drain(100L), "the drain gives up on a slow decision");

        gate.release.countDown();
        login.join();
        assertFalse(stood.get(), "a decision finished after the drain gave up must be refused");
        assertTrue(gates.drain(0L), "and it is no longer counted");
    }

    @Test
    @DisplayName("retiring a generation takes its gate and voids every lease still out")
    void retireVoidsLeases() {
        CountingGate old = new CountingGate();
        LoginGateHolder gates = runningWith(old);
        LoginGateHolder.Decision handedOut = gates.lease();

        assertEquals(1, gates.retire());

        assertNull(gates.current());
        assertFalse(gates.decide(handedOut, "login"));
        assertEquals(0, old.calls.get());
    }

    @Test
    @DisplayName("a gate that throws still gives its lease back, so a later drain is not stuck")
    void throwingDecisionIsNotLeftInFlight() {
        LoginGateHolder gates = runningWith(new LoginGate() {
            @Override
            public void decide(Object event) {
                throw new IllegalStateException("boom");
            }
        });

        assertThrows(IllegalStateException.class, () -> gates.decide(gates.await(), "login"));
        assertTrue(gates.drain(0L));
    }

    @Test
    @DisplayName("a lease given back without deciding is no longer counted, and cannot decide later")
    void releasedLeaseIsDone() {
        CountingGate gate = new CountingGate();
        LoginGateHolder gates = runningWith(gate);
        LoginGateHolder.Decision handedOut = gates.lease();

        handedOut.release();
        handedOut.release();

        assertTrue(gates.drain(0L));
        assertFalse(gates.decide(handedOut, "login"));
        assertEquals(0, gate.calls.get());
    }

    @Test
    @DisplayName("a refusal is not a lease and cannot decide")
    void refusalCannotDecide() {
        LoginGateHolder gates = new LoginGateHolder(log, 5_000L);
        gates.state(LoginGateHolder.State.DOWN);

        LoginGateHolder.Decision refusal = gates.await();

        assertThrows(IllegalArgumentException.class, () -> gates.decide(refusal, "login"));
        refusal.release();
    }

    @Test
    @DisplayName("suspend takes the gate away and holds new logins for the next one")
    void suspendHolds() {
        LoginGateHolder gates = new LoginGateHolder(log, 80L);
        gates.bind(GATE);
        gates.state(LoginGateHolder.State.RUNNING);

        gates.suspend();

        assertNull(gates.current());
        assertSame(LoginGateHolder.State.SWAPPING, gates.state());
        LoginGateHolder.Decision decision = gates.await();
        assertNull(decision.gate());
        assertEquals(ShellMessages.LOGIN_UPDATING, decision.refusal(), "held, then 'updating'");
        assertTrue(log.errors().isEmpty(), "a suspended gate is not the running-without-a-gate bug");
    }
}
