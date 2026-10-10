package com.heimdall.shell.hotswap;

import com.heimdall.core.util.Registration;
import com.heimdall.shell.contract.LoginGate;

/**
 * The shell's half of the login gate: which core gate is current, and what to do while there is
 * none.
 *
 * <h2>Fail closed, everywhere</h2>
 *
 * <p>Decided for the hot-swap design (departure D87): a login no core has decided on is never
 * admitted.
 *
 * <ul>
 *   <li><strong>Starting or swapping:</strong> the login waits up to {@link #HOLD_MS} for the next
 *       core's gate, then is refused with {@link ShellMessages#LOGIN_UPDATING}. Every platform's
 *       own login timeout is far longer than that, so the wait itself never costs a connection.
 *   <li><strong>No core</strong> (a swap and its rollback both failed, or the core could not start
 *       at all): refused at once with {@link ShellMessages#LOGIN_NO_CORE}, until a core runs again.
 *       The shell alerts admins when it enters this state.
 *   <li><strong>A core running with no gate bound</strong>: a core bug, refused at once. Every
 *       platform core binds one, so this is a guard, not a mode.
 * </ul>
 *
 * <p>A running core's own policy is unchanged and separate: its pipeline admits on an internal
 * failure, because a bug in the whitelist check must not lock everybody out. That is a decision a
 * core makes; this class is about the case where there is no core to make one.
 *
 * <p>Thread-safe. Waiters block on this object's monitor.
 */
public final class LoginGateHolder {

    /** How long a login waits for a core during a start or a swap. */
    public static final long HOLD_MS = 5_000L;

    /** Where the shell is, as far as logins are concerned. */
    public enum State {
        /** Before the first core has started; logins wait, as during a swap. */
        STARTING,
        /** A core is running. */
        RUNNING,
        /** Between two cores; logins wait. */
        SWAPPING,
        /** No core is running and none is coming; logins are refused at once. */
        DOWN
    }

    /** The outcome of {@link #await}: a gate to ask, or a refusal message. */
    public static final class Decision {

        private final LoginGate gate;
        private final String refusal;

        private Decision(LoginGate gate, String refusal) {
            this.gate = gate;
            this.refusal = refusal;
        }

        /** The gate to ask, or {@code null} when the login must be refused. */
        public LoginGate gate() {
            return gate;
        }

        /** The kick message when {@link #gate()} is {@code null}. */
        public String refusal() {
            return refusal;
        }
    }

    private final ShellLog log;
    private final Object lock = new Object();

    private LoginGate gate;
    private State state = State.STARTING;
    private boolean warnedNoGate;

    /** How long {@link #await()} waits. */
    private final long holdMs;

    public LoginGateHolder(ShellLog log) {
        this(log, HOLD_MS);
    }

    /** With a different hold, for tests that should not wait five seconds. */
    public LoginGateHolder(ShellLog log, long holdMs) {
        this.log = log;
        this.holdMs = holdMs;
    }

    /** Makes {@code next} the current gate; the handle clears it if it is still current. */
    public Registration bind(final LoginGate next) {
        if (next == null) {
            return Registration.NONE;
        }
        synchronized (lock) {
            gate = next;
            lock.notifyAll();
        }
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (gate == next) {
                        gate = null;
                    }
                }
            }
        });
    }

    /** The current gate, without waiting; {@code null} when there is none. */
    public LoginGate current() {
        synchronized (lock) {
            return gate;
        }
    }

    /** Clears the gate if its class belongs to {@code retired}; answers whether it did. */
    boolean sweep(LoadedCore retired) {
        synchronized (lock) {
            if (gate != null && retired.owns(gate.getClass())) {
                gate = null;
                return true;
            }
            return false;
        }
    }

    /** Moves to {@code next} and wakes every waiting login to look again. */
    public void state(State next) {
        synchronized (lock) {
            state = next;
            if (next == State.RUNNING) {
                warnedNoGate = false;
            }
            lock.notifyAll();
        }
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    /** {@link #await(long)} with the configured hold. */
    public Decision await() {
        return await(holdMs);
    }

    /**
     * Returns the current gate, waiting up to {@code maxWaitMs} for one while a core is starting or
     * swapping. See the class note for every refusal.
     */
    public Decision await(long maxWaitMs) {
        long deadline = System.nanoTime() + Math.max(0L, maxWaitMs) * 1_000_000L;
        synchronized (lock) {
            while (gate == null) {
                if (state == State.DOWN) {
                    return new Decision(null, ShellMessages.LOGIN_NO_CORE);
                }
                if (state == State.RUNNING) {
                    if (!warnedNoGate) {
                        warnedNoGate = true;
                        log.error("the running core has no login gate bound; refusing logins "
                                + "until one is (a core bug)", null);
                    }
                    return new Decision(null, ShellMessages.LOGIN_UPDATING);
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return new Decision(null, ShellMessages.LOGIN_UPDATING);
                }
                try {
                    long millis = remainingNanos / 1_000_000L;
                    lock.wait(Math.max(1L, millis));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new Decision(null, ShellMessages.LOGIN_UPDATING);
                }
            }
            return new Decision(gate, null);
        }
    }
}
