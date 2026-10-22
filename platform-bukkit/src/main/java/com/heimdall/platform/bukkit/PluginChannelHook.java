package com.heimdall.platform.bukkit;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.Verdict;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.bukkit.plugin.Plugin;

/**
 * The machinery every reflective chat-plugin hook shares: finding the plugin, resolving its API
 * through the plugin's own class loader, registering one post-delivery hook exactly once, noticing a
 * reload, and failing closed. What differs per plugin (which classes, which event, what "active"
 * means) is left to the subclass. Departures D85 and D92.
 *
 * <h2>Why reflection</h2>
 *
 * <p>Neither plugin can be a compile dependency of this module: ChatControl is paid and published
 * nowhere, and ZelChat's API is Java 21 bytecode while this module compiles at {@code --release 8}.
 * Every type of theirs is therefore reached by name through the plugin's <em>own</em> class loader:
 * Heimdall's loader cannot see another plugin's classes. If a release moves something, resolution
 * fails, the state becomes {@link State#BROKEN}, and everything downstream fails closed rather than
 * quietly relaying a staff channel as public chat.
 *
 * <h2>Two kinds of failure</h2>
 *
 * <p>A <em>resolution</em> failure (a class, method or field that is not there, a type that is not
 * what it was, a linkage error) means the API moved, and it is permanent: the state becomes
 * {@code BROKEN} for the rest of the boot. The plugin's <em>own code throwing</em> while Heimdall
 * calls it (an {@link InvocationTargetException}) is one call's problem: that one lookup or line
 * fails closed, a warning is logged at most once a minute, and the next call tries again.
 *
 * <h2>Reloads</h2>
 *
 * <p>A plugin manager (PlugMan, ServerUtils) can unload the plugin and load a fresh copy in a new
 * class loader. The hook and every handle here are bound to the old one, so once anything has been
 * resolved, the plugin disappearing or coming back as a different instance is {@code BROKEN} with a
 * reason that says to restart, never a quiet {@code NONE} while the new copy routes channels
 * unobserved.
 *
 * <h2>Resolution</h2>
 *
 * <p>Lazy, and retried while unresolved, for the same load-order reason as LuckPerms (#796 / MC-10):
 * {@code softdepend} makes the plugin enable first on an ordinary boot, but nothing guarantees it. A
 * missing plugin is re-looked-up at most once per retry interval, because {@code getPlugin} is
 * synchronized on the plugin manager and the chat listener asks per message. A <em>found</em> plugin
 * is cached, and only re-checked while it is disabled (see Reloads). A resolution failure is cached
 * for good, as {@code BROKEN}: a class that was not there a second ago will not be there now, and
 * retrying it per message would turn one error into a log flood.
 *
 * <p>Every public method is safe from any thread, does not block beyond the plugin's own reads, and
 * never throws: anything reflective is caught as {@link Throwable}, because the failure this class
 * exists to contain (a moved class, a changed signature) arrives as an {@link Error}.
 *
 * @param <A> the resolved reflective handles
 */
abstract class PluginChannelHook<A> implements ChannelHook {

    /** How long a "not installed" answer is trusted before asking Bukkit again. */
    static final long RETRY_NANOS = 5_000_000_000L;

    /** At most one warning per this interval for the plugin's own code throwing. */
    static final long TRANSIENT_WARN_NANOS = 60_000_000_000L;

    final HeimdallLogger logger;
    private final String pluginName;
    private final long retryNanos;

    final Object lock = new Object();

    /** The plugin once found; replaced only before anything was bound to it. */
    private volatile Plugin plugin;

    /**
     * {@link System#nanoTime()} before which a missing plugin is not looked up again. Starts at
     * construction time rather than zero, because {@code nanoTime}'s origin is arbitrary and may be
     * negative.
     */
    private volatile long nextLookupAt;

    /** {@link System#nanoTime()} before which a transient failure is not warned again. */
    private volatile long nextTransientWarnAt;

    /** The resolved reflective handles; {@code null} until the plugin is enabled and resolved. */
    volatile A api;

    /** Non-null once something reflective failed. Permanent: see the class javadoc. */
    private volatile String brokenBecause;

    /** Set once at bootstrap; the hook registers only when there is a pipeline to feed. */
    private volatile ChatPipeline pipeline;

    /** Whether the post-delivery hook is registered. Written once, by the registration claimant. */
    private volatile boolean hooked;

    /** Claimed by the one thread that attempts the registration; never released. */
    private final AtomicBoolean registrationClaimed = new AtomicBoolean();

    /** Released when that one attempt ends, either way. */
    private final CountDownLatch registrationDone = new CountDownLatch(1);

    /**
     * How long a caller waits for a registration in progress on another thread before failing
     * closed for that call. Package-private so the tests can shorten it.
     */
    volatile long registrationWaitMillis = 2000L;

    /** What the hook was registered with, so {@link #close()} can take it off again. */
    private volatile Object hookHandle;

    /** Set by {@link #close()}; no hook is registered after it. */
    private volatile boolean closed;

    /**
     * Whether the last attempt to hook found the plugin running but not {@link #ready}. Reported as
     * {@link State#BROKEN} with a reason, but not recorded: the next call tries again.
     */
    private volatile boolean awaitingReady;

    /**
     * When true, no line is relayed from the hook. Set by {@link ChatChannelHooks} to "another channel
     * plugin is installed too", because two plugins both routing chat is a state nobody can read.
     */
    private volatile BooleanSupplier relayVetoed = () -> false;

    PluginChannelHook(HeimdallLogger logger, String pluginName, long retryNanos) {
        this.logger = logger;
        this.pluginName = pluginName;
        this.retryNanos = retryNanos;
        long now = System.nanoTime();
        this.nextLookupAt = now;
        this.nextTransientWarnAt = now;
    }

    // ── What each plugin supplies ────────────────────────────────────────────

    /** The plugin if it is loaded (enabled or not), else {@code null}. */
    abstract Plugin findPlugin();

    /** The class loader the plugin's own classes live in. */
    abstract ClassLoader loaderOf(Plugin found);

    /** Resolves every handle the hook needs; throws when the API moved. */
    abstract A load(ClassLoader loader) throws ReflectiveOperationException;

    /**
     * Whether the plugin is far enough through its own startup to be hooked. A plugin is marked
     * enabled before its {@code onEnable} runs, so one that publishes its API there can briefly be
     * enabled with nothing to hook. {@code false}, or a throw, defers registration to a later call,
     * and until then the state is {@link State#BROKEN} (not recorded): a running plugin may already
     * be routing chat, and "none" would let it relay as one room. A plugin whose {@code onEnable}
     * failed stays there, which is the honest answer.
     */
    boolean ready(A resolved) throws Exception {
        return true;
    }

    /**
     * Builds what the hook is registered with, and what {@link #unregister} needs to take it off.
     * Recorded before {@link #register} is called, so a registration that half-succeeded and then
     * threw is still taken off at {@link #close()}.
     */
    abstract Object newHandle(A resolved) throws Exception;

    /**
     * Registers the post-delivery hook with {@code handle}. A throw here (or from
     * {@link #newHandle}) is a resolution failure and marks the integration broken.
     */
    abstract void register(A resolved, Object handle) throws Exception;

    /** Takes the hook off again. May throw; the caller contains it. */
    abstract void unregister(A resolved, Object handle) throws Exception;

    /** The state once the hook is live: {@link State#ACTIVE} unless the plugin's channels are off. */
    abstract State hookedState(A resolved);

    // ── ChannelHook ──────────────────────────────────────────────────────────

    @Override
    public final String pluginName() {
        return pluginName;
    }

    /**
     * Hands over the chat pipeline and tries to hook the plugin now.
     *
     * <p>Called once from the bootstrap, while Heimdall is enabling: on an ordinary boot the plugin is
     * already enabled by then ({@code softdepend}), so the hook registers here, on the main thread,
     * and the first {@link #state()} has nothing left to do.
     */
    @Override
    public final void attach(ChatPipeline chatPipeline) {
        this.pipeline = chatPipeline;
        state();
    }

    /**
     * Whether the plugin is installed at all, enabled or not, working or not.
     *
     * <p>This is what switches the legacy chat listener from "relay at NORMAL, as always" to
     * "check at NORMAL, decide at MONITOR". Presence, not health: a broken hook must still keep the
     * listener from relaying at NORMAL, or a channelled server would leak exactly when the
     * integration that knows about channels stopped working.
     */
    @Override
    public final boolean installed() {
        return plugin() != null;
    }

    /** Whether the plugin is installed and enabled, hooked or not. */
    final boolean running() {
        Plugin found = plugin();
        return found != null && isEnabled(found);
    }

    @Override
    public final State state() {
        State state = currentState();
        // null: registration is still in progress on another thread after the bounded wait. Fail
        // closed: BROKEN routes nothing by channel and broadcasts nothing, and it is not recorded,
        // so the next call answers normally once the registration lands.
        return state == null ? State.BROKEN : state;
    }

    @Override
    public final String brokenReason() {
        String why = brokenBecause;
        if (why != null) {
            return why;
        }
        return stillAwaitingReady()
                ? pluginName + " is enabled but has not published its API, so Heimdall cannot hook "
                        + "it (did " + pluginName + " fail to start?)"
                : null;
    }

    /**
     * {@link #awaitingReady}, only while it can still be true: the plugin is running and nothing is
     * hooked. A flag left behind by a plugin that has since stopped, or written late by a thread that
     * lost the race to a registration, means nothing.
     */
    private boolean stillAwaitingReady() {
        return awaitingReady && !hooked && !closed && running();
    }

    /** Called by {@link ChatChannelHooks}; see {@link #relayVetoed}. */
    final void vetoRelayWhen(BooleanSupplier veto) {
        this.relayVetoed = veto;
    }

    /**
     * {@link #state()}, except that it answers {@code null} when the hook registration is still in
     * progress on another thread after {@link #registrationWaitMillis}. Each caller maps that to its
     * own fail-closed answer.
     */
    final State currentState() {
        // Looked up first: the lookup is where a reload is noticed, and it marks the integration
        // broken, so checking brokenBecause before it would answer NONE for that one call.
        Plugin found = plugin();
        if (brokenBecause != null) {
            return State.BROKEN;
        }
        if (found == null || !isEnabled(found)) {
            // Not installed, or installed and not running: either way it is not routing chat, so
            // chat is one room.
            return State.NONE;
        }
        A resolved = resolve(found);
        if (resolved == null) {
            return brokenBecause != null ? State.BROKEN : State.NONE;
        }
        if (!hooked) {
            if (pipeline == null || !registrationClaimed.get()) {
                // No pipeline yet (bootstrap has not attached), so nothing could be relayed with a
                // channel. Not ACTIVE: that state promises the hook is live. Running but not ready
                // to be hooked is BROKEN, not NONE (see ready()), and asked again next call.
                if (brokenBecause != null) {
                    return State.BROKEN;
                }
                return pipeline != null && stillAwaitingReady() ? State.BROKEN : State.NONE;
            }
            // Another thread is inside the registration right now. The plugin may already be
            // routing channels, so NONE would be a lie (inbound, it would broadcast an untagged
            // Discord message to everybody). Wait for it, briefly: no caller here holds anything the
            // registration needs.
            if (!awaitRegistration()) {
                return null;
            }
            if (brokenBecause != null || !hooked) {
                return State.BROKEN;
            }
        }
        return hookedState(resolved);
    }

    // ── Resolution ───────────────────────────────────────────────────────────

    /**
     * The plugin, looked up again at most once per retry interval while missing, and re-checked on
     * every call while the cached instance is disabled, which is what a reload looks like from here.
     */
    private Plugin plugin() {
        Plugin found = plugin;
        if (found != null) {
            if (brokenBecause == null && !isEnabled(found)) {
                checkReloaded(found);
            }
            return plugin;
        }
        long now = System.nanoTime();
        if (now - nextLookupAt < 0) {
            return null;
        }
        try {
            found = findPlugin();
        } catch (Throwable failed) {
            found = null;
        }
        if (found == null) {
            nextLookupAt = now + retryNanos;
            return null;
        }
        plugin = found;
        return found;
    }

    /**
     * The cached plugin is disabled: find out whether it is the same plugin switched off, or gone,
     * or replaced by a fresh copy.
     *
     * <p>Switched off in place is {@code NONE}: it is not routing chat, and if it is switched back on
     * it is the same classes, so the hook is still valid. Gone or replaced, once anything was bound to
     * the old class loader, is {@code BROKEN}: the hook listens on the old copy's event class and
     * would never hear the new one route a staff line. Before anything was bound there is nothing
     * stale, so the new answer is simply adopted.
     */
    private void checkReloaded(Plugin cached) {
        // Not throttled, unlike the missing-plugin lookup. A throttle here would leave a window of
        // up to the retry interval in which a reloaded copy routes staff lines while this answers
        // NONE, and a disabled cached instance is rare (a reload, or the plugin switched off by
        // hand), so a synchronized getPlugin per call for that stretch is the cheaper risk.
        Plugin current;
        try {
            current = findPlugin();
        } catch (Throwable failed) {
            // Could not ask; keep the current answer and ask again next interval.
            return;
        }
        if (current == cached) {
            return;
        }
        synchronized (lock) {
            if (api == null && !hooked) {
                plugin = current;
                return;
            }
        }
        markBroken(current == null
                ? pluginName + " was unloaded after Heimdall hooked it; restart the server to re-hook"
                : pluginName + " was reloaded; restart the server to re-hook", null);
    }

    private static boolean isEnabled(Plugin found) {
        try {
            return found.isEnabled();
        } catch (Throwable failed) {
            return false;
        }
    }

    /**
     * Resolves the reflective handles and, once there is a pipeline and the plugin is ready,
     * registers the hook. Returns the handles, or {@code null} if resolution failed (and
     * {@link #brokenBecause} says why).
     */
    private A resolve(Plugin found) {
        A resolved = api;
        if (resolved != null && (hooked || pipeline == null)) {
            return resolved;
        }
        if (brokenBecause != null) {
            return null;
        }
        // Neither the class loading nor the registration below happens under `lock`. Loading runs
        // the plugin's static initialisers and takes class-loader locks, and a registration belongs
        // to the server's plugin manager (or the plugin's own registry), whose monitor can be held
        // while events are dispatched and plugins disabled. `lock` is taken from inside such
        // dispatches (markBroken, from the hook), so holding it across either call could close a
        // loop with them.
        if (resolved == null) {
            A loaded;
            try {
                loaded = load(loaderOf(found));
            } catch (Throwable failed) {
                markBroken(pluginName + "'s API could not be found (a " + pluginName
                        + " update may have moved it)", failed);
                return null;
            }
            synchronized (lock) {
                if (plugin != found) {
                    // Replaced while loading (a reload during the very first resolution). These
                    // handles belong to the old copy; the next call starts over with the new one.
                    return null;
                }
                if (api == null) {
                    api = loaded;
                }
                resolved = api;
            }
        }
        if (!hooked && pipeline != null && !closed && !registrationClaimed.get()) {
            awaitingReady = !readySafely(resolved);
        }
        if (!hooked && pipeline != null && !closed && !awaitingReady
                && registrationClaimed.compareAndSet(false, true)) {
            // One attempt, ever, by whichever thread claims it: a second registration would relay
            // every channel line twice. Another thread arriving meanwhile waits on
            // registrationDone (see currentState) rather than answering for a hook that may
            // already be live; the latch is released however this ends.
            boolean registered = false;
            try {
                Object handle = newHandle(resolved);
                hookHandle = handle;
                register(resolved, handle);
                hooked = true;
                awaitingReady = false;
                registered = true;
            } catch (Throwable failed) {
                markBroken("registering the " + pluginName + " channel listener failed", failed);
            } finally {
                registrationDone.countDown();
            }
            if (!registered) {
                return null;
            }
            logger.info(pluginName + " detected: Discord relay follows its chat channels");
        }
        return brokenBecause != null ? null : resolved;
    }

    /** {@link #ready}, with a throw read as "not yet" rather than as a moved API. */
    private boolean readySafely(A resolved) {
        try {
            return ready(resolved);
        } catch (Throwable notYet) {
            return false;
        }
    }

    /**
     * Takes the hook off the server, and stops any later call from registering one.
     *
     * <p>Called when this core generation stops. The hook is core code, so a hook left registered
     * after a swap would pin the old classloader and relay every channel line through a stopped
     * pipeline.
     */
    @Override
    public final void close() {
        closed = true;
        Object handle = hookHandle;
        hookHandle = null;
        A resolved = api;
        if (handle != null && resolved != null) {
            try {
                unregister(resolved, handle);
            } catch (Throwable failed) {
                logger.debug(() -> "unregistering the " + pluginName + " hook failed: " + failed);
            }
        }
    }

    /** Waits for the one registration attempt to end; {@code false} on timeout or interrupt. */
    private boolean awaitRegistration() {
        try {
            return registrationDone.await(registrationWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ── Failures ─────────────────────────────────────────────────────────────

    /**
     * Classifies a failure from a reflective call.
     *
     * <p>An {@link InvocationTargetException} is the plugin's own code throwing for this one call: a
     * rate-limited warning, and the caller fails closed for that call only. Anything else (a missing
     * member, an access or linkage error, a type that is not what it was, an argument the method no
     * longer takes) means the API moved, and the integration is broken for good.
     *
     * <p>Package-private so a test can pin the classification itself: the stand-in classes can
     * produce an {@link InvocationTargetException} naturally, but not every shape of a moved API.
     *
     * @return {@code true} if the integration is now broken
     */
    final boolean fail(String what, Throwable failure) {
        if (failure instanceof InvocationTargetException) {
            warnTransient(what, ((InvocationTargetException) failure).getCause());
            return false;
        }
        markBroken(what + " failed", failure);
        return true;
    }

    /**
     * At most one warning a minute. Names the exception's class only, never its message: this runs
     * while a chat line or a Discord message is in flight, and a message is the one place a third
     * party's code could put that text.
     */
    private void warnTransient(String what, Throwable thrown) {
        long now = System.nanoTime();
        if (now - nextTransientWarnAt < 0) {
            return;
        }
        nextTransientWarnAt = now + TRANSIENT_WARN_NANOS;
        logger.warn(pluginName + " threw "
                + (thrown == null ? "an exception" : thrown.getClass().getName())
                + " while Heimdall was " + what + "; that one relay was skipped, nothing else is "
                + "affected. Repeats are reported at most once a minute.");
    }

    /**
     * Records the integration as broken, once, with an error naming why. No chat text can reach this
     * line: the causes are resolution failures and reloads, whose messages name classes and methods.
     */
    final void markBroken(String why, Throwable cause) {
        synchronized (lock) {
            if (brokenBecause != null) {
                return;
            }
            brokenBecause = cause == null ? why : why + ": " + cause;
        }
        logger.error(pluginName + " channel integration disabled: " + why + ". Chat relay to and "
                + "from Discord is paused on this server until this is fixed, so a staff channel "
                + "cannot leak as public chat.", cause);
    }

    // ── Relay ────────────────────────────────────────────────────────────────

    /**
     * Relays one channel line the plugin delivered: Heimdall's pipeline first, read-only, then the
     * observers.
     *
     * <p>Read-only because the plugin already decided to deliver the line, and Heimdall's verdict is
     * not applied to its event: a muted or frozen player whose line it delivered anyway is still not
     * relayed, and not relaying is the whole of the consequence.
     *
     * <p>Kept apart from the hook's own reflective reads on purpose: an Error from a check or an
     * observer is a Heimdall bug, and recording it as "the plugin is broken" would pause relay for the
     * rest of the boot and blame the wrong plugin.
     */
    final void relay(ChatMessage message) {
        ChatPipeline chat = pipeline;
        if (chat == null || closed || relayVetoed.getAsBoolean()) {
            return;
        }
        try {
            Verdict verdict = chat.dispatch(message);
            if (verdict.isDeny()) {
                return;
            }
            chat.notifyObservers(message);
        } catch (Throwable failed) {
            logger.error("the chat pipeline threw on a " + pluginName + " channel line; it was "
                    + "delivered in game but not relayed", failed);
        }
    }

    /** Whether there is a pipeline to relay into yet. */
    final boolean hasPipeline() {
        return pipeline != null;
    }
}
