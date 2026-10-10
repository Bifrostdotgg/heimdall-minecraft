package com.heimdall.shell.hotswap;

import com.heimdall.core.json.Payload;
import com.heimdall.core.util.Registration;
import com.heimdall.shell.contract.CoreIdentity;
import com.heimdall.shell.contract.ShellContract;
import com.heimdall.shell.contract.StagedCore;
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
import java.util.concurrent.locks.ReentrantLock;

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
 *
 * <h2>Logins</h2>
 *
 * <p>The {@link LoginGateHolder} follows every transition: logins wait while a core starts or a swap
 * runs, are decided by the core once one is running, and are refused outright when none is. Entering
 * that last state is reported as an error on the console and to every online admin, because a
 * server that refuses every login needs a person, now.
 */
public final class ShellHost {

    /** How long a retired core's classloader stays open for work already in flight. */
    static final long LOADER_GRACE_MS = 30_000L;

    /** Where an operator stages a core (or a whole release) for {@code swap}. */
    public static final String STAGED_FILE = "staged.jar";

    /**
     * How long server shutdown waits for a swap in progress before stopping without it. Bounded so
     * a core whose start or stop hangs can never hold the server's own shutdown hostage.
     */
    static final long SHUTDOWN_WAIT_MS = 20_000L;

    /**
     * How long a swap waits, with the gate already cleared, for logins the outgoing core is still
     * deciding before it stops that core. Deliberately shorter than a login's bot-call budget: a
     * decision still out when this runs out is voided, so it is refused rather than admitted (see
     * {@link LoginGateHolder}).
     */
    static final long GATE_DRAIN_MS = 5_000L;

    /** The steps a platform shell runs between building the host and booting the first core. */
    public interface Preparation {

        void prepare(ShellHost host) throws Exception;
    }

    private final ShellPlatform platform;
    private final ShellLog log;
    private final String shellVersion;
    private final ShellTunnel tunnel;
    private final RelayTable relays;
    private final LoginGateHolder gates;

    /**
     * Held for a whole transition (boot, swap, shutdown), so two can never interleave. A lock
     * rather than a monitor so shutdown can wait for it with a bound.
     */
    private final ReentrantLock transition = new ReentrantLock();

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

    /** How long a core-requested swap is held back; a field so tests can shorten it. */
    volatile long settleMs = ShellContract.SWAP_SETTLE_MS;

    /** How long shutdown waits for a swap; a field so tests can shorten it. */
    volatile long shutdownWaitMs = SHUTDOWN_WAIT_MS;

    /** How long a swap drains logins; a field so tests can shorten it. */
    volatile long gateDrainMs = GATE_DRAIN_MS;

    public ShellHost(ShellPlatform platform, String shellVersion) {
        this(platform, shellVersion, new LoginGateHolder(platform == null ? null : platform.log()));
    }

    /**
     * With a login gate the platform shell built, and registered its listener for, before this
     * host existed. That order is what keeps the gate fail-closed even if building the host throws:
     * see {@link #enable}.
     */
    public ShellHost(ShellPlatform platform, String shellVersion, LoginGateHolder gates) {
        if (platform == null) {
            throw new IllegalArgumentException("a platform is required");
        }
        this.platform = platform;
        this.log = platform.log();
        this.shellVersion = shellVersion == null ? "unknown" : shellVersion;
        this.tunnel = new ShellTunnel(log, this.shellVersion);
        this.gates = gates == null ? new LoginGateHolder(log) : gates;
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

    /**
     * Everything a platform shell's enable does after it has registered its login listener on
     * {@code gates}: build the host, run {@code preparation} (install the permanent relays), boot.
     *
     * <p>Fail closed. If any of it throws, the gate is set {@code DOWN}, so the listener already
     * registered refuses every login rather than the platform admitting everyone with no decision
     * at all, and the failure is logged as an error. Registering the listener first is the caller's
     * half of the same rule.
     *
     * @return the host, or {@code null} if it could not even be built
     */
    public static ShellHost enable(
            ShellPlatform platform, String shellVersion, LoginGateHolder gates,
            Preparation preparation) {
        ShellHost host = null;
        try {
            host = new ShellHost(platform, shellVersion, gates);
            if (preparation != null) {
                preparation.prepare(host);
            }
            host.boot();
        } catch (Throwable failed) {
            gates.state(LoginGateHolder.State.DOWN);
            try {
                platform.log().error("Heimdall's shell could not start, so logins are refused "
                        + "until it does; restart once the cause below is fixed", failed);
            } catch (Throwable ignored) {
                // Nothing left to report through.
            }
        }
        return host;
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

    /** The running generation, for the tests. */
    Generation currentGeneration() {
        return current;
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

    /** The login gate the platform shell's permanent login listener asks. */
    public LoginGateHolder loginGate() {
        return gates;
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
        transition.lock();
        try {
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
            gates.state(LoginGateHolder.State.RUNNING);
            log.info("core " + loaded.identity() + " running (shell " + shellVersion
                    + ", contract " + ShellContract.VERSION + ")");
            return true;
        } finally {
            transition.unlock();
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
        gates.state(LoginGateHolder.State.DOWN);
        boolean locked = acquireForShutdown();
        try {
            if (locked) {
                Generation stopping = current;
                current = null;
                if (stopping != null) {
                    stop(stopping, false);
                }
            }
            relays.shutdown();
            try {
                publishedTunnel.close();
            } catch (Throwable failed) {
                log.debug("withdrawing the HeimdallTunnel service failed: " + failed);
            }
            publishedTunnel = Registration.NONE;
        } finally {
            if (locked) {
                transition.unlock();
            }
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

    /**
     * Waits, bounded, for a swap in progress to finish so shutdown can stop whatever core it left.
     * Past the bound the swap thread is interrupted and given one more short chance; after that
     * shutdown carries on without stopping the core, because the server's own shutdown must never
     * hang on a core that does.
     */
    private boolean acquireForShutdown() {
        try {
            if (transition.tryLock(shutdownWaitMs, TimeUnit.MILLISECONDS)) {
                return true;
            }
            log.warn("a core swap is still running after " + shutdownWaitMs + "ms; interrupting "
                    + "it so the server can stop");
            synchronized (executorsLock) {
                if (swapThread != null) {
                    swapThread.shutdownNow();
                }
            }
            if (transition.tryLock(2_000L, TimeUnit.MILLISECONDS)) {
                return true;
            }
            log.error("the core swap did not stop; shutting down without stopping the core", null);
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
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
     * Stages a downloaded release (or a bare core) for a live swap, after re-checking the file
     * against {@code expectedSha256}, and says whether it can be swapped in. See
     * {@code ShellContext.stageRelease}. Never throws.
     */
    public StagedCore stageForSwap(Path file, String expectedSha256) {
        if (!Sha256.isWellFormed(expectedSha256)) {
            return StagedCore.unusable("no verified checksum came with this release, so it cannot "
                    + "be swapped in live");
        }
        CoreArchive.CoreJar jar;
        try {
            String actual = Sha256.of(file);
            if (!actual.equals(expectedSha256)) {
                return StagedCore.unusable("the downloaded release no longer matches its checksum "
                        + "(expected " + expectedSha256.substring(0, 12) + ", found "
                        + actual.substring(0, 12) + ")");
            }
            jar = stage(file);
        } catch (CoreArchiveException unusable) {
            return StagedCore.unusable(unusable.getMessage());
        } catch (Throwable broken) {
            return StagedCore.unusable("staging failed unexpectedly: " + broken);
        }
        if (jar.contract() != ShellContract.VERSION) {
            return new StagedCore(jar.path(), jar.identity(), jar.contract(),
                    "core " + jar.identity() + " was built for shell contract " + jar.contract()
                            + " and this shell implements " + ShellContract.VERSION
                            + ", so it needs a restart");
        }
        if (jar.identity().equals(runningCore())) {
            return new StagedCore(jar.path(), jar.identity(), jar.contract(),
                    "core " + jar.identity() + " is already running");
        }
        return new StagedCore(jar.path(), jar.identity(), jar.contract(), "");
    }

    /**
     * Starts a live swap to a core {@link #stageForSwap} accepted; see
     * {@code ShellContext.swapTo}.
     */
    public boolean swapTo(StagedCore staged, Object sender) {
        if (staged == null || !staged.swappable()) {
            return false;
        }
        CoreArchive.CoreJar jar;
        try {
            jar = CoreArchive.describe(staged.path());
        } catch (CoreArchiveException gone) {
            log.warn("the staged core is no longer usable: " + gone.getMessage());
            return false;
        }
        if (!jar.identity().equals(staged.identity())) {
            // Content-addressed files cannot change under the same name, so this is a file an
            // operator replaced by hand between staging and swapping. Refuse rather than guess.
            log.warn("the staged core changed between staging and swapping; refusing the swap");
            return false;
        }
        // Held back by settleMs, so the core that asked can still report before the swap stops it.
        final CoreArchive.CoreJar verified = jar;
        return requestSwap(new CoreSource() {
            @Override
            public CoreArchive.CoreJar get() {
                return verified;
            }
        }, new AudienceListener(platform.audience(), sender), settleMs);
    }

    /** Produces the core a swap goes to; runs on the swap thread. */
    interface CoreSource {

        CoreArchive.CoreJar get() throws CoreArchiveException;
    }

    /**
     * Starts a swap to {@code jar} on the swap thread and returns at once.
     *
     * @return {@code false} if a swap is already queued or running, or the shell is stopping
     */
    public boolean requestSwap(final CoreArchive.CoreJar jar, final SwapListener listener) {
        return requestSwap(new CoreSource() {
            @Override
            public CoreArchive.CoreJar get() {
                return jar;
            }
        }, listener, 0L);
    }

    /**
     * Stages {@code file} and swaps to it, both on the swap thread, and returns at once.
     *
     * <p>For {@code /hd swap}, which arrives on whatever thread the platform dispatches commands on:
     * the main thread on the Bukkit family. Staging copies and hashes a jar of a few megabytes, which
     * does not belong on a tick.
     */
    public boolean requestSwapFromFile(final Path file, SwapListener listener) {
        return requestSwap(new CoreSource() {
            @Override
            public CoreArchive.CoreJar get() throws CoreArchiveException {
                return stage(file);
            }
        }, listener, 0L);
    }

    private boolean requestSwap(
            final CoreSource source, final SwapListener listener, final long delayMs) {
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
                        if (delayMs > 0) {
                            Thread.sleep(delayMs);
                        }
                        outcome = swapTo(source, told);
                    } catch (InterruptedException stopping) {
                        // Shutdown interrupts the swap thread, most often during the settle delay.
                        // Nothing failed: the swap was cancelled, and saying so at ERROR would
                        // read as a fault in every shutdown log.
                        Thread.currentThread().interrupt();
                        log.info("a pending core swap was cancelled because the server is stopping");
                        outcome = new SwapOutcome(SwapOutcome.Kind.REFUSED,
                                "the swap was cancelled: the server is stopping", runningCore());
                    } catch (Throwable broken) {
                        if (shutDown) {
                            log.info("a core swap was cut short by server shutdown: " + broken);
                        } else {
                            log.error("the swap failed unexpectedly", broken);
                        }
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

    /** Gets the core from {@code source} and swaps to it; an unusable core is a refusal. */
    private SwapOutcome swapTo(CoreSource source, SwapListener listener) {
        CoreArchive.CoreJar jar;
        try {
            jar = source.get();
        } catch (CoreArchiveException unusable) {
            return refuse("the staged core cannot be used: " + unusable.getMessage(), listener);
        }
        return swap(jar, listener);
    }

    /**
     * Swaps the running core for {@code jar}, synchronously. See the class note for the sequence.
     *
     * <p>Package-private and synchronous for the tests; production goes through
     * {@link #requestSwap}.
     */
    SwapOutcome swap(CoreArchive.CoreJar jar, SwapListener listener) {
        transition.lock();
        try {
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

            // Logins hold from here, never refused: this matters most when there is no outgoing
            // core, where the state was DOWN and would otherwise refuse logins through the start.
            swapping = true;
            gates.state(LoginGateHolder.State.SWAPPING);
            try {
                tell(listener, "Swapping " + from + " for core " + incoming.identity() + "...");
                Map<String, Object> handoff = Collections.emptyMap();
                if (outgoing != null) {
                    // New logins now wait for the next core. Logins the outgoing core already holds a
                    // lease for get a bounded time to finish; any still out after it are voided, so
                    // whatever the stopped core decides for them, they are refused, never admitted.
                    gates.suspend();
                    long drainMs = gateDrainMs;
                    if (!gates.drain(drainMs)) {
                        log.warn("a login was still being decided after " + drainMs
                                + "ms; it will be refused, and the old core is stopping");
                    }
                    handoff = stop(outgoing, true);
                    current = null;
                    sweep(outgoing.loaded);
                    tell(listener, "Stopped " + from + "; starting core " + incoming.identity()
                            + "...");
                }

                Generation started = start(incoming, handoff);
                if (started != null) {
                    current = started;
                    lastProblem = "";
                    gates.state(LoginGateHolder.State.RUNNING);
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
                if (current == null) {
                    gates.state(LoginGateHolder.State.DOWN);
                }
                int pruned = relays.prune();
                if (pruned > 0) {
                    log.debug("removed " + pruned + " command(s) the new core did not register");
                }
            }
        } finally {
            transition.unlock();
        }
    }

    private SwapOutcome rollBack(
            Generation outgoing,
            LoadedCore failed,
            Map<String, Object> handoff,
            SwapListener listener) {
        log.warn("core " + failed.identity() + " failed to start; rolling back to core "
                + outgoing.loaded.identity());
        tell(listener, "Core " + failed.identity() + " failed to start; rolling back...");
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
        gates.state(LoginGateHolder.State.RUNNING);
        lastProblem = "core " + failed.identity() + " failed to start; still running core "
                + again.identity();
        log.warn("rolled back: " + lastProblem);
        return new SwapOutcome(SwapOutcome.Kind.ROLLED_BACK,
                "The new core failed to start, so the previous core " + again.identity()
                        + " is running again. Check the server log for why.",
                again.identity());
    }

    /**
     * A progress line for {@code listener}. A listener that throws (a platform sender that has
     * gone away) must not abort a swap halfway, with the gate suspended and no core started.
     */
    private void tell(SwapListener listener, String line) {
        try {
            listener.progress(line);
        } catch (Throwable failed) {
            log.debug("a swap progress line could not be delivered: " + failed);
        }
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

    /**
     * Records and reports that no core is running, and closes the login gate. Loud on purpose: the
     * console gets an error and every online admin gets told, because from here every login is
     * refused until somebody acts.
     */
    private void noCore(String why, Throwable cause) {
        lastProblem = why;
        gates.state(LoginGateHolder.State.DOWN);
        log.error(why + ". Heimdall is not running, and logins are refused until a core starts: "
                + "stage a core and run /" + platform.adminLabel() + " swap, or restart.", cause);
        try {
            platform.audience().alertOnline(ShellMessages.ADMIN_PERMISSION,
                    "§4[Heimdall] §cNo core is running, so every login is being refused. "
                            + "Check the server log, then run §f/" + platform.adminLabel()
                            + " status§c.");
        } catch (Throwable unavailable) {
            // The console line above is the alert that matters.
        }
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
        // Its gate goes before it stops, and any login still holding a lease on it is voided: a
        // decision finished by a stopping core is refused, never admitted. A swap has already
        // drained by now; this is for a core that failed during its start, and for shutdown.
        int voided = gates.retire();
        if (voided > 0) {
            log.warn(voided + " login(s) were still being decided by core "
                    + generation.loaded.identity() + " as it stopped; they will be refused");
        }
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
        if (gates.sweep(retired)) {
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

    Registration bindLoginGate(com.heimdall.shell.contract.LoginGate gate) {
        return gates.bind(gate);
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
