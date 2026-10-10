package com.heimdall.shell.hotswap;

import com.heimdall.core.util.Registration;
import com.heimdall.shell.contract.LoginGate;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

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
 * <h2>Leases: a decision made by a retired core does not count</h2>
 *
 * <p>A gate is handed out as a <em>lease</em> ({@link #await}, {@link #lease}), counted from that
 * moment, under the same lock that hands it out, and released when {@link #decide} returns. A swap
 * takes the gate away ({@link #suspend}) and then waits a bounded time for every lease to come back
 * ({@link #drain}). Counting from the hand-out, not from the call into the gate, is what closes the
 * window between "this login got the old core's gate" and "it started deciding". If the drain gives
 * up, or a generation is stopped with leases still out ({@link #retire}), those leases are
 * <strong>voided</strong>: {@link #decide} then answers that the decision does not stand, whatever
 * the old core decided, and the platform refuses the login with
 * {@link ShellMessages#LOGIN_UPDATING}. So a slow decision (a bot call can take far longer than the
 * drain waits) can never admit a player through a core that has since been stopped, where an
 * API-fallback "allow" would otherwise let it.
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

    /**
     * The outcome of {@link #await}: a leased gate to ask, or a refusal message. A lease is given
     * back by {@link LoginGateHolder#decide}, or by {@link #release()} on a path that will not
     * decide.
     */
    public static final class Decision {

        private final LoginGate gate;
        private final String refusal;
        private final LoginGateHolder lessor;

        /** Guarded by the lessor's lock. */
        private boolean released;
        private boolean voided;

        private Decision(LoginGate gate, String refusal, LoginGateHolder lessor) {
            this.gate = gate;
            this.refusal = refusal;
            this.lessor = lessor;
        }

        /** Gives the lease back without deciding. Idempotent; a refusal has nothing to give. */
        public void release() {
            if (lessor != null) {
                lessor.release(this);
            }
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

    /** Leases handed out and not yet given back; guarded by {@link #lock}. */
    private final Set<Decision> leased =
            Collections.newSetFromMap(new IdentityHashMap<Decision, Boolean>());
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

    /**
     * The current gate as a lease, without waiting, or {@code null} when none is bound. For a
     * platform that decides synchronously when it can and waits elsewhere when it cannot.
     */
    public Decision lease() {
        synchronized (lock) {
            return gate == null ? null : leaseLocked();
        }
    }

    private Decision leaseLocked() {
        Decision lease = new Decision(gate, null, this);
        leased.add(lease);
        return lease;
    }

    /**
     * Asks the leased gate to decide {@code event} and gives the lease back. Platform listeners
     * call this rather than {@code gate.decide} directly.
     *
     * @return whether the decision stands. {@code false} when the lease was voided (the drain gave
     *     up on it, or its core was stopped) before or while it decided: the platform must then
     *     refuse the login with {@link ShellMessages#LOGIN_UPDATING}, whatever the event now says.
     *     A lease voided before deciding never reaches the gate at all.
     * @throws IllegalArgumentException for a refusal, which has no gate to decide with
     */
    public boolean decide(Decision lease, Object event) {
        if (lease == null || lease.gate == null || lease.lessor != this) {
            throw new IllegalArgumentException("not a lease from this holder");
        }
        boolean live;
        synchronized (lock) {
            live = !lease.released && !lease.voided;
        }
        try {
            if (live) {
                lease.gate.decide(event);
            }
        } finally {
            release(lease);
        }
        synchronized (lock) {
            return live && !lease.voided;
        }
    }

    private void release(Decision lease) {
        synchronized (lock) {
            if (!lease.released) {
                lease.released = true;
                leased.remove(lease);
                lock.notifyAll();
            }
        }
    }

    /** Voids every lease still out; guarded by {@link #lock}. */
    private int voidLeasesLocked() {
        int voided = 0;
        for (Decision lease : leased) {
            if (!lease.voided) {
                lease.voided = true;
                voided++;
            }
        }
        return voided;
    }

    /**
     * Takes the current gate away for a swap: logins arriving from now on wait for the next core
     * (the state is {@link State#SWAPPING}) instead of reaching a core that is about to stop.
     */
    public void suspend() {
        synchronized (lock) {
            gate = null;
            state = State.SWAPPING;
            lock.notifyAll();
        }
    }

    /**
     * Waits up to {@code maxWaitMs} for every lease to come back. Any still out when it gives up
     * are voided, so none of them can admit a login after the core that leased it is stopped.
     *
     * @return whether every lease came back in time
     */
    public boolean drain(long maxWaitMs) {
        long deadline = System.nanoTime() + Math.max(0L, maxWaitMs) * 1_000_000L;
        synchronized (lock) {
            while (!leased.isEmpty()) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    voidLeasesLocked();
                    return false;
                }
                try {
                    lock.wait(Math.max(1L, remainingNanos / 1_000_000L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    voidLeasesLocked();
                    return leased.isEmpty();
                }
            }
            return true;
        }
    }

    /**
     * A generation is being stopped: takes its gate away and voids every lease still out, without
     * waiting. The swap has already drained by the time it stops the outgoing core, so this matters
     * where nothing drained first: a core that failed during its start, and server shutdown.
     *
     * @return how many leases were voided
     */
    int retire() {
        synchronized (lock) {
            gate = null;
            int voided = voidLeasesLocked();
            lock.notifyAll();
            return voided;
        }
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
     * Returns the current gate as a lease, waiting up to {@code maxWaitMs} for one while a core is
     * starting or swapping. See the class note for every refusal, and for leases.
     */
    public Decision await(long maxWaitMs) {
        long deadline = System.nanoTime() + Math.max(0L, maxWaitMs) * 1_000_000L;
        synchronized (lock) {
            while (gate == null) {
                if (state == State.DOWN) {
                    return new Decision(null, ShellMessages.LOGIN_NO_CORE, null);
                }
                if (state == State.RUNNING) {
                    if (!warnedNoGate) {
                        warnedNoGate = true;
                        log.error("the running core has no login gate bound; refusing logins "
                                + "until one is (a core bug)", null);
                    }
                    return new Decision(null, ShellMessages.LOGIN_UPDATING, null);
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return new Decision(null, ShellMessages.LOGIN_UPDATING, null);
                }
                try {
                    long millis = remainingNanos / 1_000_000L;
                    lock.wait(Math.max(1L, millis));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new Decision(null, ShellMessages.LOGIN_UPDATING, null);
                }
            }
            return leaseLocked();
        }
    }
}
