package com.heimdall.shell.hotswap;

import com.heimdall.core.json.Payload;
import com.heimdall.core.util.Registration;
import com.heimdall.shell.contract.CoreIdentity;
import com.heimdall.shell.contract.ShellContract;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the running core generation: loads it at server start, swaps it live, rolls a failed swap
 * back, and stops it at server stop.
 *
 * <p>The platform shells construct one of these in their enable hook and do little else. Everything
 * that is not a platform API call lives here, so it is written once and tested without a server.
 *
 * <h2>Where the core comes from at start</h2>
 *
 * <p>Always from the jar the platform loaded: the core nested inside it is extracted to
 * {@code <data>/core/} under a content-addressed name, checked against the hash the build recorded,
 * and loaded from there. A core swapped in live is therefore a property of this process only, and a
 * restart returns to whatever the installed jar carries. The updater keeps the two in step by staging
 * the whole release for the restart as well as swapping its core in (departure D87).
 *
 * <h2>The swap, in order</h2>
 *
 * <ol>
 *   <li><strong>Load the new core first</strong>, while the old one keeps running. A jar that cannot
 *       be read, is built for another contract, or whose entry point will not construct is refused
 *       here, and nothing about the running server has changed.
 *   <li><strong>Stop the old core</strong>, with {@code isSwapping()} true so it hands state over
 *       instead of only stopping. Then close whatever it left tracked (newest first), then sweep the
 *       platform and the shell's own state for anything still pointing into its classloader.
 *   <li><strong>Start the new core</strong> with the old one's handoff.
 *   <li><strong>If that throws, roll back</strong>: stop what the new core half-built and start the
 *       old core again from its jar, in a <em>fresh</em> classloader (never by restarting the old
 *       instance, whose static state has already been torn down). If the rollback fails too, no core
 *       is running and the shell says so as loudly as it can.
 *   <li><strong>Close the old classloader later</strong>, after {@link #LOADER_GRACE_MS}. Closing it
 *       makes every not-yet-loaded class fail to load, and old-core work that was already in flight
 *       (a scheduled task, a socket callback) can still be finishing.
 * </ol>
 *
 * <p>All of it runs on the shell's own {@code heimdall-swap} thread. Stopping a core waits for its
 * executors to drain, which can take seconds; on a server thread that is a watchdog crash.
 */
public final class ShellHost {

    /** How long a retired core's classloader stays open for work already in flight. */
    static final long LOADER_GRACE_MS = 30_000L;

    /** Where an operator stages a core (or a whole release) for {@code swap}. */
    public static final String STAGED_FILE = "staged.jar";

    private final ShellPlatform platform;
    private final ShellLog log;
    private final String shellVersion;
    private final ShellTunnel tunnel;
    private final RelayTable relays;

    /** Held for a whole transition (boot, swap, shutdown), so two can never interleave. */
    private final Object transition = new Object();

    private volatile Generation current;
    private volatile boolean swapping;
    private volatile boolean shutDown;
    private volatile String lastProblem = "";

    private final AtomicBoolean swapQueued = new AtomicBoolean();
    private final Object executorsLock = new Object();
    private ExecutorService swapThread;
    private ScheduledThreadPoolExecutor timer;
    private Registration publishedTunnel = Registration.NONE;

    /** How long a retired loader stays open; a field so tests can shorten it. */
    volatile long loaderGraceMs = LOADER_GRACE_MS;

    public ShellHost(ShellPlatform platform, String shellVersion) {
        if (platform == null) {
            throw new IllegalArgumentException("a platform is required");
        }
        this.platform = platform;
        this.log = platform.log();
        this.shellVersion = shellVersion == null ? "unknown" : shellVersion;
        this.tunnel = new ShellTunnel(log, this.shellVersion);
        this.relays = new RelayTable(platform.commands(), platform.audience(), log,
                new RelayTable.State() {
                    @Override
                    public boolean isSwapping() {
                        return swapping;
                    }

                    @Override
                    public boolean hasCore() {
                        return current != null;
                    }

                    @Override
                    public String adminLabel() {
                        return ShellHost.this.platform.adminLabel();
                    }
                });
        this.relays.admin(new ShellAdmin(this, platform.audience()));
    }

    /** One loaded core and the context it was started with. */
    static final class Generation {

        final LoadedCore loaded;
        final GenerationContext context;

        Generation(LoadedCore loaded, GenerationContext context) {
            this.loaded = loaded;
            this.context = context;
        }
    }

    // ── State ────────────────────────────────────────────────────────────────

    /** The shell's own version. */
    public String shellVersion() {
        return shellVersion;
    }

    /** Whether a swap is in progress. */
    public boolean isSwapping() {
        return swapping;
    }

    /** The running core, or {@code null} when none is. */
    public CoreIdentity runningCore() {
        Generation running = current;
        return running == null ? null : running.loaded.identity();
    }

    /** Why the last start, swap or rollback failed; empty when nothing has. */
    public String lastProblem() {
        return lastProblem;
    }

    /** Where cores are written: {@code <data>/core}. */
    public Path coreDirectory() {
        return platform.dataDirectory().resolve("core");
    }

    /** Where an operator stages a core for {@code swap}. */
    public Path stagedPath() {
        return coreDirectory().resolve(STAGED_FILE);
    }

    /** The command relays. The platform shell installs its permanent ones here before booting. */
    public RelayTable relays() {
        return relays;
    }

    /** The permanent tunnel other plugins hold. */
    public ShellTunnel tunnel() {
        return tunnel;
    }

    ShellPlatform platform() {
        return platform;
    }

    ShellLog log() {
        return log;
    }

    // ── Start and stop ───────────────────────────────────────────────────────

    /**
     * Publishes the tunnel, then extracts, loads and starts the core this jar carries.
     *
     * <p>Never throws. A core that cannot be read, loaded or started is reported, and the shell runs
     * on with no core.
     *
     * @return whether a core is now running
     */
    public boolean boot() {
        synchronized (transition) {
            try {
                publishedTunnel = platform.publishTunnel(tunnel);
            } catch (Throwable failed) {
                log.warn("could not publish the HeimdallTunnel service: " + failed);
            }
            CoreArchive.CoreJar jar;
            try {
                CoreArchive.Embedded embedded = readOwnCore();
                Path written = CoreArchive.materialise(embedded, coreDirectory());
                jar = CoreArchive.describe(written);
                CoreArchive.prune(coreDirectory(),
                        Collections.singleton(written.toAbsolutePath().normalize()), log);
            } catch (CoreArchiveException unusable) {
                noCore("Heimdall's core could not be read from the plugin jar: "
                        + unusable.getMessage(), null);
                return false;
            }
            LoadedCore loaded;
            try {
                loaded = CoreLoader.load(jar, platform.shellLoader(), ShellContract.VERSION);
            } catch (CoreArchiveException refused) {
                noCore("Heimdall's core could not be loaded: " + refused.getMessage(),
                        refused.getCause());
                return false;
            }
            Generation started = start(loaded, Collections.<String, Object>emptyMap());
            if (started == null) {
                noCore("Heimdall's core " + loaded.identity() + " failed to start; see the error "
                        + "above", null);
                return false;
            }
            current = started;
            lastProblem = "";
            log.info("core " + loaded.identity() + " running (shell " + shellVersion
                    + ", contract " + ShellContract.VERSION + ")");
            return true;
        }
    }

    /**
     * Stops the running core because the server is stopping, and withdraws the shell's commands and
     * tunnel.
     *
     * <p>The loader is deliberately left open. The JVM is about to exit (or, on a {@code /reload},
     * about to drop every reference), and closing it now would turn any callback still finishing on a
     * library thread into a {@code NoClassDefFoundError} stack trace in the shutdown log.
     */
    public void shutdown() {
        shutDown = true;
        synchronized (transition) {
            Generation stopping = current;
            current = null;
            if (stopping != null) {
                stop(stopping, false);
            }
            relays.shutdown();
            try {
                publishedTunnel.close();
            } catch (Throwable failed) {
                log.debug("withdrawing the HeimdallTunnel service failed: " + failed);
            }
            publishedTunnel = Registration.NONE;
        }
        synchronized (executorsLock) {
            if (swapThread != null) {
                swapThread.shutdownNow();
            }
            if (timer != null) {
                // Pending loader closes are dropped on purpose: see the method note.
                timer.shutdownNow();
            }
        }
    }

    // ── Staging and swapping ─────────────────────────────────────────────────

    /**
     * Turns a file into a loadable core jar in {@code <data>/core/}: a release jar has its core
     * extracted and checked against the recorded hash, a bare core jar is copied in.
     */
    public CoreArchive.CoreJar stage(Path file) throws CoreArchiveException {
        if (CoreArchive.isRelease(file)) {
            CoreArchive.Embedded embedded = CoreArchive.readEmbedded(file);
            return CoreArchive.describe(CoreArchive.materialise(embedded, coreDirectory()));
        }
        CoreArchive.CoreJar described = CoreArchive.describe(file);
        Path adopted = CoreArchive.adopt(file, described.identity(), coreDirectory());
        return CoreArchive.describe(adopted);
    }

    /**
     * Starts a swap to {@code jar} on the swap thread and returns at once.
     *
     * @return {@code false} if a swap is already queued or running, or the shell is stopping
     */
    public boolean requestSwap(final CoreArchive.CoreJar jar, final SwapListener listener) {
        if (shutDown || !swapQueued.compareAndSet(false, true)) {
            return false;
        }
        final SwapListener told = listener == null ? SwapListener.NONE : listener;
        try {
            swapExecutor().execute(new Runnable() {
                @Override
                public void run() {
                    SwapOutcome outcome;
                    try {
                        outcome = swap(jar, told);
                    } catch (Throwable broken) {
                        log.error("the swap failed unexpectedly", broken);
                        outcome = new SwapOutcome(SwapOutcome.Kind.REFUSED,
                                "the swap failed unexpectedly: " + broken, runningCore());
                    } finally {
                        swapQueued.set(false);
                    }
                    try {
                        told.finished(outcome);
                    } catch (Throwable ignored) {
                        // The listener is reporting. The swap itself is over either way.
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException stopping) {
            swapQueued.set(false);
            return false;
        }
    }

    /**
     * Swaps the running core for {@code jar}, synchronously. See the class note for the sequence.
     *
     * <p>Package-private and synchronous for the tests; production goes through
     * {@link #requestSwap}.
     */
    SwapOutcome swap(CoreArchive.CoreJar jar, SwapListener listener) {
        synchronized (transition) {
            if (shutDown) {
                return refuse("the server is stopping", listener);
            }
            Generation outgoing = current;
            if (outgoing != null && outgoing.loaded.identity().equals(jar.identity())) {
                return refuse("core " + jar.identity() + " is already running", listener);
            }
            LoadedCore incoming;
            try {
                incoming = CoreLoader.load(jar, platform.shellLoader(), ShellContract.VERSION);
            } catch (CoreArchiveException refused) {
                return refuse(refused.getMessage(), listener);
            }

            String from = outgoing == null ? "no core" : "core " + outgoing.loaded.identity();
            log.info("swapping " + from + " for core " + incoming.identity());
            listener.progress("Swapping " + from + " for core " + incoming.identity() + "...");

            swapping = true;
            try {
                Map<String, Object> handoff = Collections.emptyMap();
                if (outgoing != null) {
                    handoff = stop(outgoing, true);
                    current = null;
                    sweep(outgoing.loaded);
                }

                Generation started = start(incoming, handoff);
                if (started != null) {
                    current = started;
                    lastProblem = "";
                    if (outgoing != null) {
                        closeLater(outgoing.loaded);
                    }
                    String done = "core " + incoming.identity() + " is running (was "
                            + (outgoing == null ? "none" : outgoing.loaded.identity().toString())
                            + ")";
                    log.info(done);
                    return new SwapOutcome(SwapOutcome.Kind.SWAPPED, done, incoming.identity());
                }

                // The new core failed to start and has already been unwound. Its loader is no use.
                sweep(incoming);
                closeLater(incoming);
                if (outgoing == null) {
                    return noCoreOutcome("core " + incoming.identity() + " failed to start and "
                            + "there was no previous core to return to");
                }
                return rollBack(outgoing, incoming, handoff, listener);
            } finally {
                swapping = false;
                int pruned = relays.prune();
                if (pruned > 0) {
                    log.debug("removed " + pruned + " command(s) the new core did not register");
                }
            }
        }
    }

    private SwapOutcome rollBack(
            Generation outgoing,
            LoadedCore failed,
            Map<String, Object> handoff,
            SwapListener listener) {
        log.warn("core " + failed.identity() + " failed to start; rolling back to core "
                + outgoing.loaded.identity());
        listener.progress("Core " + failed.identity() + " failed to start; rolling back...");
        LoadedCore again;
        try {
            CoreArchive.CoreJar previous = CoreArchive.describe(outgoing.loaded.jar());
            again = CoreLoader.load(previous, platform.shellLoader(), ShellContract.VERSION);
        } catch (CoreArchiveException unusable) {
            closeLater(outgoing.loaded);
            return noCoreOutcome("core " + failed.identity() + " failed to start, and the "
                    + "previous core could not be reloaded: " + unusable.getMessage());
        }
        // The old instance's classloader is no longer needed either: the rollback runs in a fresh
        // one, so nothing it defined is reused.
        closeLater(outgoing.loaded);
        Generation restored = start(again, handoff);
        if (restored == null) {
            sweep(again);
            closeLater(again);
            return noCoreOutcome("core " + failed.identity() + " failed to start, and so did the "
                    + "rollback to core " + outgoing.loaded.identity());
        }
        current = restored;
        lastProblem = "core " + failed.identity() + " failed to start; still running core "
                + again.identity();
        log.warn("rolled back: " + lastProblem);
        return new SwapOutcome(SwapOutcome.Kind.ROLLED_BACK,
                "The new core failed to start, so the previous core " + again.identity()
                        + " is running again. Check the server log for why.",
                again.identity());
    }

    private SwapOutcome refuse(String why, SwapListener listener) {
        log.warn("swap refused: " + why);
        return new SwapOutcome(SwapOutcome.Kind.REFUSED, why, runningCore());
    }

    private SwapOutcome noCoreOutcome(String why) {
        current = null;
        noCore(why, null);
        return new SwapOutcome(SwapOutcome.Kind.NO_CORE,
                why + ". Heimdall is not running; restart the server.", null);
    }

    /** Records and reports that no core is running. */
    private void noCore(String why, Throwable cause) {
        lastProblem = why;
        log.error(why + ". Heimdall is not running until a core starts.", cause);
    }

    // ── One generation ───────────────────────────────────────────────────────

    /**
     * Starts {@code loaded} with a fresh context, or unwinds it.
     *
     * @return the running generation, or {@code null} if it failed to start (already unwound)
     */
    Generation start(LoadedCore loaded, Map<String, Object> handoff) {
        GenerationContext context =
                new GenerationContext(this, platform, loaded.identity(), handoff);
        Generation generation = new Generation(loaded, context);
        try {
            loaded.core().start(context);
            return generation;
        } catch (Throwable failed) {
            log.error("core " + loaded.identity() + " failed to start", failed);
            stop(generation, false);
            return null;
        }
    }

    /**
     * Stops a generation and closes whatever it left tracked. Never throws.
     *
     * @param forSwap whether a successor is about to start, which is what lets the core hand over
     * @return what the core handed over (empty unless {@code forSwap})
     */
    Map<String, Object> stop(Generation generation, boolean forSwap) {
        generation.context.beginStopping(forSwap);
        try {
            generation.loaded.core().stop();
        } catch (Throwable failed) {
            log.error("core " + generation.loaded.identity() + " did not stop cleanly", failed);
        }
        int leftovers = generation.context.retire(log);
        if (leftovers > 0) {
            log.warn("core " + generation.loaded.identity() + " left " + leftovers
                    + " registration(s) behind; the shell undid them");
        }
        return generation.context.leftBehind();
    }

    /** Removes anything still pointing into {@code retired}'s classloader, platform and shell. */
    private void sweep(LoadedCore retired) {
        int swept = 0;
        try {
            swept += platform.sweep(retired);
        } catch (Throwable failed) {
            log.warn("sweeping the platform after core " + retired.identity() + " failed: "
                    + failed);
        }
        swept += relays.sweep(retired);
        if (tunnel.sweep(retired)) {
            swept++;
        }
        if (swept > 0) {
            log.warn("removed " + swept + " registration(s) that still pointed at core "
                    + retired.identity() + " after it stopped");
        }
    }

    /** Closes {@code retired}'s loader after the grace period, on the shell timer. */
    private void closeLater(final LoadedCore retired) {
        try {
            timer().schedule(new Runnable() {
                @Override
                public void run() {
                    retired.closeLoader(log);
                    log.debug("closed the classloader of core " + retired.identity());
                }
            }, loaderGraceMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopping) {
            // The shell is shutting down; the JVM is about to drop the loader anyway.
        }
    }

    // ── Context callbacks ────────────────────────────────────────────────────

    Registration bindCommand(com.heimdall.shell.contract.CommandBinding binding) {
        return relays.bind(binding);
    }

    Registration bindTunnel(com.heimdall.shell.contract.TunnelBackend backend) {
        return tunnel.bind(backend);
    }

    boolean deliverUnclaimed(String requestId, String type, Payload payload) {
        return tunnel.deliver(requestId, type, payload);
    }

    // ── Threads ──────────────────────────────────────────────────────────────

    private ExecutorService swapExecutor() {
        synchronized (executorsLock) {
            if (swapThread == null) {
                swapThread = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<Runnable>(), daemon("heimdall-swap"));
            }
            return swapThread;
        }
    }

    private ScheduledThreadPoolExecutor timer() {
        synchronized (executorsLock) {
            if (timer == null) {
                timer = new ScheduledThreadPoolExecutor(1, daemon("heimdall-shell-timer"));
                timer.setRemoveOnCancelPolicy(true);
            }
            return timer;
        }
    }

    /**
     * Daemon threads with a fixed name, created with the shell's loader as their context loader so
     * no thread the shell owns can pin a core's.
     */
    private ThreadFactory daemon(final String name) {
        final AtomicInteger count = new AtomicInteger();
        final ClassLoader shellLoader = ShellHost.class.getClassLoader();
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, name + "-" + count.incrementAndGet());
                thread.setDaemon(true);
                thread.setContextClassLoader(shellLoader);
                return thread;
            }
        };
    }

    // ── Reading the installed jar ────────────────────────────────────────────

    /**
     * The core nested in the installed jar.
     *
     * <p>Read through a fresh {@code JarFile} when the platform names the jar, and through the shell's
     * own loader only when it does not. At start the two are the same file.
     */
    private CoreArchive.Embedded readOwnCore() throws CoreArchiveException {
        File jar = platform.shellJar();
        if (jar != null && jar.isFile()) {
            return CoreArchive.readEmbedded(jar.toPath());
        }
        InputStream properties =
                platform.shellLoader().getResourceAsStream(ShellContract.EMBEDDED_CORE_PROPERTIES);
        InputStream nested = platform.shellLoader().getResourceAsStream(ShellContract.EMBEDDED_CORE);
        try {
            if (properties == null || nested == null) {
                throw new CoreArchiveException("the plugin jar carries no embedded core");
            }
            return CoreArchive.readEmbedded(properties, nested);
        } finally {
            closeQuietly(properties);
            closeQuietly(nested);
        }
    }

    /** Whether a staged file exists. */
    boolean hasStagedFile() {
        return Files.isRegularFile(stagedPath());
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // Read-only.
        }
    }
}
