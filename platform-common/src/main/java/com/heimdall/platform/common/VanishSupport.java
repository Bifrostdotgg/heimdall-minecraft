package com.heimdall.platform.common;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.platform.Vanish;
import com.heimdall.core.platform.VanishListener;
import com.heimdall.core.util.Registration;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The platform-neutral half of the PremiumVanish integration: resolution, the toggle listeners,
 * containment and teardown, written once for all three platforms. Departure D88.
 *
 * <p>Each platform subclasses this and supplies only what genuinely differs between them: how to
 * find the plugin and its class loader, which static method answers "is this player invisible" for
 * that platform's player type, and how to register for its hide and show events. Everything that
 * would otherwise be written three times and drift (when to resolve, what to do when the API has
 * moved, how a throwing listener is contained, what closing means for a hot swap) lives here.
 *
 * <h2>Reflection, and why</h2>
 *
 * <p>PremiumVanish's API is MIT and published, but the plugin itself is paid and optional, and its
 * API jar must never be shipped: PremiumVanish loads the real classes, and its stub classes log a
 * severe warning if anything else loads them. So every type is reached by name through
 * PremiumVanish's <em>own</em> class loader, exactly as the ChatControl hook does (D85), and a server
 * without it never loads a class of its. The one exception is BungeeCord's event bus, which can only
 * dispatch to a method whose declared parameter is the event class; see {@code BungeeVanish}.
 *
 * <h2>Resolution</h2>
 *
 * <p>Lazy, and retried on every call while the plugin is missing: there is no load-order guarantee
 * between plugins (the same reason as LuckPerms, #796 / MC-10), and the callers ask at human rates
 * (a join, a quit, a death), so an unthrottled lookup costs nothing worth designing around. A found
 * plugin is cached, and checked to still be the live one on every call, so a plugin manager that
 * reloads PremiumVanish into a new class loader is followed rather than left listening to the old
 * copy's events.
 *
 * <p>A <em>resolution</em> failure (a class or method that is not there) means the API moved, and it
 * is permanent for this core: it is logged once, as an error naming the consequence, and the
 * integration answers as if PremiumVanish were absent. That is fail-open, deliberately and unlike
 * ChatControl's fail-closed: failing closed here would mean relaying no join or leave for anybody,
 * which punishes every player for a staff feature, and the PremiumVanish calls used here have not
 * changed in years. PremiumVanish's <em>own code</em> throwing on one call is that call's problem:
 * it answers {@code false} and warns at most once a minute.
 *
 * <h2>Hot swap</h2>
 *
 * <p>The event hook is core code registered on the platform, so it is tracked through
 * {@link CoreRegistrations} like every other registration a generation makes (D87): a swap closes it
 * whether or not {@link #close()} ran, and a hook registered after the generation was retired is
 * closed on arrival. {@link #close()} also stops anything later from registering one.
 *
 * <p>Every public method is safe from any thread and never throws.
 */
public abstract class VanishSupport implements Vanish, AutoCloseable {

    /**
     * How long a join waits for PremiumVanish to mark a player, when it is installed. A backend marks
     * an auto-vanished player inside the join itself; this is for a proxy, which learns a player's
     * state from a backend or from PremiumVanish's shared database a moment later.
     */
    public static final long JOIN_SETTLE_MS = 1500L;

    /** At most one transient warning per this interval. */
    static final long TRANSIENT_WARN_NANOS = TimeUnit.SECONDS.toNanos(60);

    /** How long a caller waits for another thread's resolution before answering "absent". */
    static final long RESOLVE_WAIT_MS = 2000L;

    /**
     * One resolved PremiumVanish: the reflective handles a platform found through its class loader.
     *
     * <p>Methods may throw anything; the base class contains it.
     */
    protected interface Binding {

        /** Whether the plugin this was resolved from is still the live, enabled one. */
        boolean current() throws Throwable;

        /** Whether PremiumVanish has {@code player} hidden, online or not. */
        boolean vanished(PlayerHandle player) throws Throwable;

        /**
         * Registers for the hide and show events, delivering each through {@link #toggled}.
         *
         * @return a handle that unregisters exactly what this registered
         */
        Registration hook() throws Throwable;
    }

    protected final HeimdallLogger logger;
    private final CoreRegistrations registrations;
    private final CopyOnWriteArrayList<VanishListener> listeners =
            new CopyOnWriteArrayList<VanishListener>();

    /** Serialises resolution, never held across anything a caller of this class waits on. */
    private final ReentrantLock resolveLock = new ReentrantLock();

    private volatile Binding bound;
    private volatile Registration hooks = Registration.NONE;
    private volatile boolean closed;
    private volatile String brokenBecause;
    private volatile long nextTransientWarnAt;

    protected VanishSupport(HeimdallLogger logger, CoreRegistrations registrations) {
        this.logger = logger;
        this.registrations = registrations == null ? CoreRegistrations.untracked() : registrations;
        this.nextTransientWarnAt = System.nanoTime();
    }

    /**
     * Finds PremiumVanish and resolves what this platform needs from it.
     *
     * @return the binding, or {@code null} when PremiumVanish is not installed or not enabled
     * @throws Throwable when it is installed and its API could not be resolved (it moved)
     */
    protected abstract Binding bind() throws Throwable;

    /**
     * A vanish signal that needs no plugin to be found, checked before PremiumVanish. The Bukkit
     * family reads the {@code vanished} metadata convention here; the proxies have nothing.
     */
    protected boolean vanishedByConvention(PlayerHandle player) throws Throwable {
        return false;
    }

    /**
     * Resolves now, so the event hook is in place before anybody joins. Called by the bootstrap; a
     * miss is simply retried by the next call.
     */
    public final void prepare() {
        binding();
    }

    /** Whether PremiumVanish is installed and resolved. For the bootstrap's log and the tests. */
    public final boolean present() {
        return binding() != null;
    }

    @Override
    public final long joinSettleMillis() {
        return binding() != null ? JOIN_SETTLE_MS : 0L;
    }

    @Override
    public final boolean isVanished(PlayerHandle player) {
        if (player == null) {
            return false;
        }
        try {
            if (vanishedByConvention(player)) {
                return true;
            }
        } catch (Throwable failed) {
            // A third party's metadata value threw while being read. One call's problem.
            warnTransient("reading the vanished metadata", failed);
        }
        Binding binding = binding();
        if (binding == null) {
            return false;
        }
        try {
            return binding.vanished(player);
        } catch (Throwable failed) {
            if (failed instanceof InvocationTargetException) {
                warnTransient("checking whether a player is vanished",
                        ((InvocationTargetException) failed).getCause());
            } else {
                markBroken("checking whether a player is vanished failed", failed);
            }
            return false;
        }
    }

    @Override
    public final Registration onToggle(final VanishListener listener) {
        if (listener == null || closed) {
            return Registration.NONE;
        }
        listeners.add(listener);
        // Resolving here is what puts the event hook in place when nothing has asked yet, which is
        // the shape of a hot swap: players are already online, and no join will come along to
        // trigger it.
        binding();
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                listeners.remove(listener);
            }
        });
    }

    /**
     * Delivers a hide or show to every listener. Called by a platform's event hook, on the vanish
     * plugin's own thread. Never throws: a throwing listener is logged and the rest still run.
     */
    protected final void toggled(PlayerHandle player, boolean vanished, boolean silent) {
        if (closed || player == null) {
            return;
        }
        long observedAt = System.currentTimeMillis();
        for (VanishListener listener : listeners) {
            try {
                listener.onVanishChanged(player, vanished, silent, observedAt);
            } catch (Throwable failed) {
                logger.error("a vanish listener threw; the change was still delivered to the rest",
                        failed);
            }
        }
    }

    /** How many toggle listeners are subscribed. For tests. */
    final int listenerCount() {
        return listeners.size();
    }

    /** Takes the event hook off the platform and stops anything later from registering one. */
    @Override
    public final void close() {
        closed = true;
        Registration current = hooks;
        hooks = Registration.NONE;
        bound = null;
        listeners.clear();
        closeQuietly(current);
    }

    // ── Resolution ───────────────────────────────────────────────────────────

    private Binding binding() {
        if (closed || brokenBecause != null) {
            return null;
        }
        Binding current = bound;
        if (current != null && isCurrent(current)) {
            return current;
        }
        boolean locked;
        try {
            // Bounded, so a resolution stuck behind a server lock on another thread costs this
            // caller one "absent" answer rather than a hung thread.
            locked = resolveLock.tryLock(RESOLVE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (!locked) {
            return null;
        }
        try {
            return resolveLocked();
        } finally {
            resolveLock.unlock();
        }
    }

    private Binding resolveLocked() {
        if (closed || brokenBecause != null) {
            return null;
        }
        Binding current = bound;
        if (current != null) {
            if (isCurrent(current)) {
                return current;
            }
            // Disabled or reloaded: the hook listens to the old copy's events. Take it off and look
            // again; a re-enabled or reloaded PremiumVanish is bound afresh below.
            bound = null;
            Registration stale = hooks;
            hooks = Registration.NONE;
            closeQuietly(stale);
            logger.info("PremiumVanish was disabled or reloaded; Heimdall will hook it again when it "
                    + "is back");
        }
        Binding fresh;
        try {
            fresh = bind();
        } catch (Throwable failed) {
            markBroken("PremiumVanish's API could not be found (a PremiumVanish update may have "
                    + "moved it)", failed);
            return null;
        }
        if (fresh == null) {
            return null;
        }
        Registration hook = Registration.NONE;
        try {
            hook = fresh.hook();
        } catch (Throwable failed) {
            // Queries still work without the hook; only mid-session toggles go unseen.
            logger.error("could not register for PremiumVanish's vanish and reappear events; joins, "
                    + "leaves and deaths of vanished players are still kept out of Discord, but a "
                    + "player vanishing mid-session will not be noticed until they leave", failed);
        }
        Registration tracked = registrations.track(hook == null ? Registration.NONE : hook);
        bound = fresh;
        hooks = tracked;
        if (closed) {
            // close() ran while this was resolving: it saw no hook to take off, so this does.
            bound = null;
            hooks = Registration.NONE;
            closeQuietly(tracked);
            return null;
        }
        logger.info("PremiumVanish detected: joins, leaves and deaths of vanished players are not "
                + "relayed to Discord");
        return fresh;
    }

    private static boolean isCurrent(Binding binding) {
        try {
            return binding.current();
        } catch (Throwable failed) {
            return false;
        }
    }

    private static void closeQuietly(Registration registration) {
        try {
            registration.close();
        } catch (Throwable ignored) {
            // Teardown of a hook on a plugin that is itself going away. Nothing to do about it here.
        }
    }

    /**
     * Records the integration as broken for this core, once, with an error naming the consequence.
     * Nothing a player typed can reach this line: the causes name classes and methods.
     */
    private void markBroken(String why, Throwable cause) {
        synchronized (this) {
            if (brokenBecause != null) {
                return;
            }
            brokenBecause = why;
        }
        Registration current = hooks;
        hooks = Registration.NONE;
        bound = null;
        closeQuietly(current);
        logger.error("PremiumVanish integration disabled: " + why + ". Vanished players' joins, "
                + "leaves and deaths may be relayed to Discord on this server until Heimdall is "
                + "updated.", cause);
    }

    /** At most one warning a minute, naming the exception's class only. */
    private void warnTransient(String what, Throwable thrown) {
        long now = System.nanoTime();
        if (now - nextTransientWarnAt < 0) {
            return;
        }
        nextTransientWarnAt = now + TRANSIENT_WARN_NANOS;
        logger.warn("a vanish plugin threw "
                + (thrown == null ? "an exception" : thrown.getClass().getName()) + " while Heimdall "
                + "was " + what + "; that one check answered 'not vanished'. Repeats are reported at "
                + "most once a minute.");
    }

    /** Why the integration is broken, or {@code null}. For tests and the status line. */
    public final String brokenReason() {
        return brokenBecause;
    }
}
