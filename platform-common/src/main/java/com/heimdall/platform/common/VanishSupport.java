package com.heimdall.platform.common;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.platform.Vanish;
import com.heimdall.core.platform.VanishListener;
import com.heimdall.core.util.Registration;
import java.lang.reflect.InvocationTargetException;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * The platform-neutral half of the PremiumVanish integration: resolution, the join wait, the toggle
 * listeners, containment and teardown, written once for all three platforms. Departure D88.
 *
 * <p>Each platform subclasses this and supplies only what genuinely differs between them: how to
 * find the plugin and its class loader, which static method answers "is this player invisible" for
 * that platform's player type, and how to register for its events. Everything that would otherwise
 * be written three times and drift (when to resolve, what a failure means, how long a join waits,
 * how a throwing listener is contained, what closing means for a hot swap) lives here.
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
 * (a join, a quit, a death), so an unthrottled lookup costs nothing worth designing around. A miss is
 * never recorded as a failure, so a {@link #prepare()} that runs before PremiumVanish has loaded
 * costs nothing. A found plugin is cached, and checked to still be the live one on every call, so a
 * plugin manager that reloads PremiumVanish into a new class loader is followed rather than left
 * listening to the old copy's events.
 *
 * <h2>Two kinds of failure</h2>
 *
 * <ul>
 *   <li><strong>The API moved</strong> (a {@link LinkageError}, or a reflective lookup failing):
 *       permanent for this core. Logged once as an error naming the consequence, and the integration
 *       answers as if PremiumVanish were absent. Fail-open, deliberately and unlike ChatControl's
 *       fail-closed: failing closed here would silence every player's join for good. An event class
 *       that is missing is not this: it costs only the mid-session toggles (older PremiumVanish
 *       releases shipped the query API before the events), and the queries keep working.
 *   <li><strong>One call threw</strong> (PremiumVanish's own code, say a database timeout): that call
 *       answers <em>vanished</em>. One missing join message beats publishing a hidden staff member,
 *       and the next call asks again. Warned at most once a minute.
 * </ul>
 *
 * <h2>How long a join waits</h2>
 *
 * <p>Nothing at all without PremiumVanish. With it, a backend waits {@link #JOIN_SETTLE_MS} from the
 * join: PremiumVanish marks an auto-vanished player inside the join itself, so this is margin. A
 * proxy cannot count from the join, because its PremiumVanish only learns a player's state once
 * they have reached a backend and that backend has told it, which can take longer than the whole
 * window. So a proxy counts the window from the player's first backend connection after the join
 * ({@link #backendConnected}), with a cap of {@link #PROXY_JOIN_CAP_MS} from the join for a player
 * who never reaches one: at the cap the join is decided with what is known.
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

    /** How long a join waits after its anchor (the join, or a proxy's backend connection). */
    public static final long JOIN_SETTLE_MS = 1500L;

    /**
     * A proxy's cap on the wait, from the join, for a player who has not reached a backend. Long
     * enough for a slow backend handshake, short enough that a join still reads as news.
     */
    public static final long PROXY_JOIN_CAP_MS = 10_000L;

    /** At most one transient warning per this interval. */
    static final long TRANSIENT_WARN_NANOS = TimeUnit.SECONDS.toNanos(60);

    /** How long a caller waits for another thread's resolution before answering "absent". */
    static final long RESOLVE_WAIT_MS = 2000L;

    /** Backend connections older than this are forgotten; no join waits anywhere near as long. */
    static final long BACKEND_MEMORY_NANOS = TimeUnit.SECONDS.toNanos(60);

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
         * Registers for the events this platform needs: hide and show (delivered through
         * {@link #toggled}) when the release has them, and on a proxy the backend connection (through
         * {@link #backendConnected}).
         *
         * @return a handle that unregisters exactly what this registered
         */
        Registration hook() throws Throwable;
    }

    protected final HeimdallLogger logger;
    private final CoreRegistrations registrations;
    private final boolean proxy;
    private final CopyOnWriteArrayList<VanishListener> listeners =
            new CopyOnWriteArrayList<VanishListener>();

    /** Serialises resolution, never held across anything a caller of this class waits on. */
    private final ReentrantLock resolveLock = new ReentrantLock();

    /** First backend connection per player, on {@link #nanoNow()}'s clock. Proxies only. */
    private final ConcurrentHashMap<UUID, Long> backendConnections =
            new ConcurrentHashMap<UUID, Long>();

    private volatile Binding bound;
    private volatile Registration hooks = Registration.NONE;
    private volatile boolean closed;
    private volatile String brokenBecause;
    private volatile long nextTransientWarnAt;
    private volatile LongSupplier clock;

    /**
     * @param proxy whether this platform is a proxy, whose join wait is anchored to the player's
     *     first backend connection rather than to the join
     */
    protected VanishSupport(HeimdallLogger logger, CoreRegistrations registrations, boolean proxy) {
        this.logger = logger;
        this.registrations = registrations == null ? CoreRegistrations.untracked() : registrations;
        this.proxy = proxy;
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
     * Resolves now, so the event hook is in place before anybody joins. Called by the bootstrap. A
     * miss (PremiumVanish loads after Heimdall) is not a failure, and is simply retried on first use.
     */
    public final void prepare() {
        binding();
    }

    /** Whether PremiumVanish is installed and resolved. For the tests. */
    public final boolean present() {
        return binding() != null;
    }

    @Override
    public final long joinWaitMillis(PlayerHandle player, long elapsedMs) {
        if (player == null || binding() == null) {
            return 0L;
        }
        long elapsed = Math.max(0L, elapsedMs);
        if (!proxy) {
            return Math.max(0L, JOIN_SETTLE_MS - elapsed);
        }
        long now = nanoNow();
        long joinedAt = now - TimeUnit.MILLISECONDS.toNanos(elapsed);
        Long connected = player.uuid() == null ? null : backendConnections.get(player.uuid());
        if (connected != null && connected.longValue() - joinedAt < 0) {
            // A connection from an earlier session; this join's has not happened yet.
            backendConnections.remove(player.uuid(), connected);
            connected = null;
        }
        if (connected == null) {
            return Math.max(0L, PROXY_JOIN_CAP_MS - elapsed);
        }
        long sinceConnect = TimeUnit.NANOSECONDS.toMillis(now - connected.longValue());
        return Math.max(0L, JOIN_SETTLE_MS - sinceConnect);
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
            // A third party's metadata value threw while being read. That one check answers
            // "vanished", for the same reason as a throwing PremiumVanish call below.
            warnTransient("reading the vanished metadata", failed, ANSWERED_VANISHED);
            return true;
        }
        Binding binding = binding();
        if (binding == null) {
            return false;
        }
        try {
            return binding.vanished(player);
        } catch (Throwable failed) {
            if (movedApi(failed)) {
                markBroken("checking whether a player is vanished failed", failed);
                return false;
            }
            warnTransient("checking whether a player is vanished",
                    failed instanceof InvocationTargetException
                            ? ((InvocationTargetException) failed).getCause()
                            : failed, ANSWERED_VANISHED);
            return true;
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

    /**
     * Records that a player reached a backend. Called by a proxy's connection hook. Only the first
     * connection after a join counts, so a server switch inside the wait does not extend it.
     */
    protected final void backendConnected(UUID player) {
        if (closed || player == null) {
            return;
        }
        long now = nanoNow();
        backendConnections.putIfAbsent(player, now);
        // Bounded by the players who connected in the last minute; nothing waits longer than that.
        Iterator<Map.Entry<UUID, Long>> entries = backendConnections.entrySet().iterator();
        while (entries.hasNext()) {
            if (now - entries.next().getValue().longValue() > BACKEND_MEMORY_NANOS) {
                entries.remove();
            }
        }
    }

    /** How many toggle listeners are subscribed. For tests. */
    final int listenerCount() {
        return listeners.size();
    }

    /** Substitutes the monotonic clock the join wait reads. For tests. */
    final void clockForTests(LongSupplier nanos) {
        this.clock = nanos;
    }

    private long nanoNow() {
        LongSupplier supplied = clock;
        return supplied != null ? supplied.getAsLong() : System.nanoTime();
    }

    /** Takes the event hook off the platform and stops anything later from registering one. */
    @Override
    public final void close() {
        closed = true;
        Registration current = hooks;
        hooks = Registration.NONE;
        bound = null;
        listeners.clear();
        backendConnections.clear();
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
            if (movedApi(failed)) {
                markBroken("PremiumVanish's API could not be found (a PremiumVanish update may have "
                        + "moved it)", failed);
            } else {
                // Not a moved API: something threw while looking (the server, the plugin). Absent
                // for this call, asked again on the next.
                warnTransient("looking for PremiumVanish", failed,
                        "that call treated PremiumVanish as absent and the next one looks again");
            }
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
            logger.error("could not register for PremiumVanish's events; joins, leaves and deaths "
                    + "of vanished players are still kept out of Discord, but a player vanishing "
                    + "mid-session will not be noticed until they leave", failed);
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

    /**
     * Whether a failure means PremiumVanish's API is not what this integration was written against:
     * a linkage error, or a reflective lookup that failed. An {@link InvocationTargetException} is
     * the opposite (PremiumVanish's own code threw), and anything else is treated as one call's
     * problem too.
     */
    private static boolean movedApi(Throwable failed) {
        if (failed instanceof InvocationTargetException) {
            return false;
        }
        return failed instanceof LinkageError || failed instanceof ReflectiveOperationException;
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

    private static final String ANSWERED_VANISHED =
            "that one check answered 'vanished', so nothing was posted for it";

    /** At most one warning a minute, naming the exception's class only. */
    private void warnTransient(String what, Throwable thrown, String outcome) {
        long now = System.nanoTime();
        if (now - nextTransientWarnAt < 0) {
            return;
        }
        nextTransientWarnAt = now + TRANSIENT_WARN_NANOS;
        logger.warn("a vanish plugin threw "
                + (thrown == null ? "an exception" : thrown.getClass().getName()) + " while Heimdall "
                + "was " + what + "; " + outcome + ". Repeats are reported at most once a minute.");
    }

    /** Why the integration is broken, or {@code null}. For tests. */
    public final String brokenReason() {
        return brokenBecause;
    }
}
