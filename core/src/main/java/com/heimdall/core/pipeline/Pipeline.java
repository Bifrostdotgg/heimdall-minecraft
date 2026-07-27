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
 * <p>Priority is explicit and ties break by registration order, so the outcome does not depend on
 * which module happened to enable first. A whitelist gate and a ban check that swapped places
 * because the dashboard toggled them in a different order would show players the wrong reason for
 * being kept out, which is a support conversation rather than a bug report.
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
     * Registers a check.
     *
     * @param priority lower runs earlier; ties break by registration order
     * @return a handle that removes exactly this registration
     */
    public final Registration register(final Interceptor<C> interceptor, int priority) {
        if (interceptor == null) {
            throw new IllegalArgumentException("interceptor is required");
        }
        final Entry<C> entry = new Entry<C>(interceptor, priority, sequence.incrementAndGet());
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
                // Treated as an abstain, not as a denial and not as an allow. A broken check must
                // not be able to lock a server's whole player base out, and must not be able to
                // wave them all through either.
                logger.error(name + " interceptor at priority " + entry.priority + " threw", e);
                continue;
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

        Entry(Interceptor<C> interceptor, int priority, long sequence) {
            this.interceptor = interceptor;
            this.priority = priority;
            this.sequence = sequence;
        }
    }
}
