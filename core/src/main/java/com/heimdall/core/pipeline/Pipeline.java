package com.heimdall.core.pipeline;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An ordered chain of checks over one kind of event, with the first denial winning.
 *
 * <h2>Execution</h2>
 *
 * <p>Interceptors run in ascending priority order. The first {@link Verdict.Decision#DENY} stops the
 * chain — nothing after it runs, and nothing after it can overturn it. If every interceptor
 * abstains, the pipeline's configured default applies.
 *
 * <p><strong>Everything is synchronous, on the calling thread.</strong> See {@link Interceptor} for
 * why, and for whose job the blocking budget is.
 *
 * <h2>Registration order is not execution order</h2>
 *
 * <p>Priority is explicit and <strong>ties break by registration order, stably</strong>: two checks
 * registered at the same priority always run in the order they were registered, however many times
 * the chain is rebuilt around them. That matters because the chain is rebuilt on every module
 * enable and disable, and a sort that was not stable would let a whitelist gate and a ban check
 * swap places when an unrelated module was toggled — showing players the wrong reason for being
 * kept out, which is a support conversation rather than a bug report. The tiebreaker is an explicit
 * monotonic sequence rather than a reliance on the sort's stability, so it holds regardless of what
 * {@code Collections.sort} promises.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #dispatch} is lock-free against an immutable snapshot of the chain, so a module being
 * enabled mid-login cannot make a login see a half-built chain. Registration and removal are
 * synchronized and rebuild the snapshot.
 *
 * @param <C> the immutable context type
 */
public class Pipeline<C> {

    private final String name;
    private final HeimdallLogger logger;
    private final Verdict.Decision defaultDecision;

    private final Object writeLock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final List<Entry<C>> registered = new ArrayList<Entry<C>>();

    /** The ordered snapshot {@link #dispatch} walks. Replaced wholesale on every change. */
    private volatile List<Entry<C>> ordered = Collections.emptyList();

    /**
     * @param defaultDecision what an all-abstain run means; must be {@code ALLOW} or {@code DENY}
     */
    protected Pipeline(String name, HeimdallLogger logger, Verdict.Decision defaultDecision) {
        if (logger == null) {
            throw new IllegalArgumentException("logger is required");
        }
        if (defaultDecision == Verdict.Decision.ABSTAIN) {
            throw new IllegalArgumentException(
                    "the fallthrough decision has to be an actual decision, not another abstain");
        }
        this.name = name;
        this.logger = logger;
        this.defaultDecision = defaultDecision;
    }

    /** This pipeline's name, used in log lines. */
    public final String name() {
        return name;
    }

    /** What an all-abstain run decides. */
    public final Verdict.Decision defaultDecision() {
        return defaultDecision;
    }

    /**
     * Registers a check, attributed to whatever registered it.
     *
     * @param priority lower runs earlier; <strong>ties break by registration order</strong>, and
     *     that stability is deliberate — see the class javadoc
     * @param owner what to name in a log line when this check throws, typically a module id
     * @return a handle that removes exactly this registration
     */
    public final Registration register(final Interceptor<C> interceptor, int priority, String owner) {
        if (interceptor == null) {
            throw new IllegalArgumentException("interceptor is required");
        }
        final Entry<C> entry = new Entry<C>(
                interceptor, priority, sequence.incrementAndGet(), describe(interceptor, owner));
        synchronized (writeLock) {
            registered.add(entry);
            rebuild();
        }
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                synchronized (writeLock) {
                    registered.remove(entry);
                    rebuild();
                }
            }
        });
    }

    /** Registers a check with no owner but its own class name. */
    public final Registration register(Interceptor<C> interceptor, int priority) {
        return register(interceptor, priority, null);
    }

    /**
     * Something usable in a log line.
     *
     * <p>A lambda's class name is {@code SomeModule$$Lambda$42/0x00...}, which tells an operator
     * nothing, so the owner the caller supplied wins whenever there is one.
     */
    private static String describe(Interceptor<?> interceptor, String owner) {
        if (owner != null && !owner.isEmpty()) {
            return owner;
        }
        return interceptor.getClass().getName();
    }

    /** How many checks are currently registered. */
    public final int size() {
        return ordered.size();
    }

    /**
     * Runs the chain.
     *
     * @return the winning verdict: the first denial, or the default rendered as {@link
     *     Verdict#allow()} / a reasonless {@link Verdict#deny}
     */
    public final Verdict dispatch(C context) {
        for (Entry<C> entry : ordered) {
            Verdict verdict;
            try {
                verdict = entry.interceptor.intercept(context);
            } catch (RuntimeException e) {
                // An escaped exception is a bug in the check, and it is reported as one — SEVERE,
                // naming the module, because on the login pipeline the consequence is that a gate
                // an operator believes is running silently is not.
                logger.error(name + " interceptor '" + entry.owner + "' (priority " + entry.priority
                        + ") threw; applying its declared failure verdict", e);
                verdict = failureVerdictOf(entry, e);
            }
            if (verdict == null || verdict.isAbstain()) {
                continue;
            }
            if (verdict.isDeny()) {
                return verdict;
            }
        }
        return defaultDecision == Verdict.Decision.ALLOW
                ? Verdict.allow()
                : Verdict.deny(null);
    }

    /** Drops every registration. Used only when the plugin is stopping. */
    public final void clear() {
        synchronized (writeLock) {
            registered.clear();
            rebuild();
        }
    }

    /**
     * What a throwing interceptor decided, according to itself.
     *
     * <p>Guarded in turn: an interceptor whose failure handling also throws is treated as having
     * abstained, because at that point nothing it says can be trusted and the pipeline still has to
     * return.
     */
    private Verdict failureVerdictOf(Entry<C> entry, RuntimeException cause) {
        try {
            Verdict declared = entry.interceptor.failureVerdict(cause);
            return declared == null ? Verdict.abstain() : declared;
        } catch (RuntimeException e) {
            logger.error(name + " interceptor '" + entry.owner + "' also threw from "
                    + "failureVerdict(); treating it as an abstain", e);
            return Verdict.abstain();
        }
    }

    /** Must be called under {@link #writeLock}. */
    private void rebuild() {
        List<Entry<C>> copy = new ArrayList<Entry<C>>(registered);
        Collections.sort(copy, new Comparator<Entry<C>>() {
            @Override
            public int compare(Entry<C> left, Entry<C> right) {
                if (left.priority != right.priority) {
                    return left.priority < right.priority ? -1 : 1;
                }
                return left.sequence < right.sequence ? -1 : (left.sequence > right.sequence ? 1 : 0);
            }
        });
        ordered = Collections.unmodifiableList(copy);
    }

    /** One registration: the check, its priority, and the tiebreaker. */
    private static final class Entry<C> {

        private final Interceptor<C> interceptor;
        private final int priority;
        private final long sequence;
        private final String owner;

        Entry(Interceptor<C> interceptor, int priority, long sequence, String owner) {
            this.interceptor = interceptor;
            this.priority = priority;
            this.sequence = sequence;
            this.owner = owner;
        }
    }
}
