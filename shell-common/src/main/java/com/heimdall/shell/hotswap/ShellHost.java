package com.heimdall.shell.hotswap;

import com.heimdall.shell.contract.CoreIdentity;
import com.heimdall.shell.contract.ShellContract;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;

/**
 * Owns the running core generation: loads it at server start and stops it at server stop.
 *
 * <p>The platform shells construct one of these in their enable hook and do almost nothing else.
 * Everything that is not a platform API call lives here, so it is written once and tested without a
 * server.
 *
 * <h2>Where the core comes from at start</h2>
 *
 * <p>Always from the jar the platform loaded: the core nested inside it is extracted to
 * {@code <data>/core/} under a content-addressed name, checked against the hash the build recorded,
 * and loaded from there. A core swapped in live is therefore a property of this process only, and a
 * restart returns to whatever the installed jar carries. The updater keeps the two in step by staging
 * the whole release for the restart as well as swapping its core in (departure D87).
 */
public final class ShellHost {

    private final ShellPlatform platform;
    private final ShellLog log;
    private final String shellVersion;

    private final Object lock = new Object();

    /** The running generation, or {@code null} when no core is running. Guarded by {@link #lock}. */
    private Generation current;

    private volatile boolean swapping;

    public ShellHost(ShellPlatform platform, String shellVersion) {
        if (platform == null) {
            throw new IllegalArgumentException("a platform is required");
        }
        this.platform = platform;
        this.log = platform.log();
        this.shellVersion = shellVersion == null ? "unknown" : shellVersion;
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
        synchronized (lock) {
            return current == null ? null : current.loaded.identity();
        }
    }

    /** Where cores are written: {@code <data>/core}. */
    public Path coreDirectory() {
        return platform.dataDirectory().resolve("core");
    }

    /**
     * Extracts, loads and starts the core this jar carries.
     *
     * <p>Never throws. A core that cannot be read, loaded or started is reported, and the shell runs
     * on with no core.
     *
     * @return whether a core is now running
     */
    public boolean boot() {
        CoreArchive.CoreJar jar;
        try {
            CoreArchive.Embedded embedded = readOwnCore();
            Path written = CoreArchive.materialise(embedded, coreDirectory());
            jar = CoreArchive.describe(written);
            CoreArchive.prune(coreDirectory(),
                    Collections.singleton(written.toAbsolutePath().normalize()), log);
        } catch (CoreArchiveException unusable) {
            log.error("Heimdall's core could not be read from the plugin jar: "
                    + unusable.getMessage(), null);
            return false;
        }
        LoadedCore loaded;
        try {
            loaded = CoreLoader.load(jar, platform.shellLoader(), ShellContract.VERSION);
        } catch (CoreArchiveException refused) {
            log.error("Heimdall's core could not be loaded: " + refused.getMessage(),
                    refused.getCause());
            return false;
        }
        Generation started = start(loaded);
        if (started == null) {
            return false;
        }
        synchronized (lock) {
            current = started;
        }
        log.info("core " + loaded.identity() + " running (shell " + shellVersion + ", contract "
                + ShellContract.VERSION + ")");
        return true;
    }

    /**
     * Stops the running core because the server is stopping.
     *
     * <p>The loader is deliberately left open. The JVM is about to exit (or, on a {@code /reload},
     * about to drop every reference), and closing it now would turn any callback still finishing on a
     * library thread into a {@code NoClassDefFoundError} stack trace in the shutdown log.
     */
    public void shutdown() {
        Generation stopping;
        synchronized (lock) {
            stopping = current;
            current = null;
        }
        if (stopping == null) {
            return;
        }
        stop(stopping);
    }

    /**
     * Starts {@code loaded} with a fresh context, or unwinds it.
     *
     * @return the running generation, or {@code null} if it failed to start (already unwound)
     */
    Generation start(LoadedCore loaded) {
        GenerationContext context = new GenerationContext(this, platform, loaded.identity());
        Generation generation = new Generation(loaded, context);
        try {
            loaded.core().start(context);
            return generation;
        } catch (Throwable failed) {
            log.error("core " + loaded.identity() + " failed to start", failed);
            stop(generation);
            return null;
        }
    }

    /** Stops a generation and closes whatever it left tracked. Never throws. */
    void stop(Generation generation) {
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
    }

    /**
     * The core nested in the installed jar.
     *
     * <p>Read through a fresh {@code JarFile} when the platform names the jar, and through the shell's
     * own loader only when it does not. At start the two are the same file; the difference only
     * matters later, after the updater has replaced the jar on disk, and nothing later reads this.
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
