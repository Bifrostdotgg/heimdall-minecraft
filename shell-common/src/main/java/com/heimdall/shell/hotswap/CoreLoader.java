package com.heimdall.shell.hotswap;

import com.heimdall.shell.contract.HeimdallCore;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Loads a core jar into a fresh child classloader and constructs its entry point.
 *
 * <h2>Parent-first, and why that is enough</h2>
 *
 * <p>The loader is a plain two-argument {@code URLClassLoader} (the named constructor does not exist
 * on Java 8) whose parent is the shell's own loader. Standard parent-first delegation then gives the
 * split its meaning: the contract, the public API and Gson resolve from the shell, once, for every
 * generation, and everything else resolves from the core jar. The build's {@code verifyJarSplit}
 * guarantees the two jars share no class, because a class present in both would always resolve from
 * the shell and the core's copy would silently never be upgraded.
 *
 * <h2>Checks before anything runs</h2>
 *
 * <ul>
 *   <li>The manifest's contract must equal the shell's. This is the cheap check, made before a
 *       loader exists.
 *   <li>The entry class must be defined by the new loader itself. If it came from the parent, the
 *       core is not really in this jar, and starting it would start whatever the shell happens to
 *       carry.
 *   <li>{@link HeimdallCore#contractVersion()} must agree as well. The manifest is what the build
 *       wrote; this is what the code was compiled against. They disagreeing is a broken build.
 * </ul>
 *
 * <p>The services entry is read straight from the jar by {@link CoreArchive}, not through
 * {@code ServiceLoader}: on Java 8 that would also return providers visible through the parent, and
 * it opens the jar through the JDK's shared {@code JarURLConnection} cache.
 */
public final class CoreLoader {

    private CoreLoader() {
    }

    /**
     * Loads {@code jar} and constructs its core, or explains why not.
     *
     * <p>Nothing is started. On any failure the new loader is closed before this returns.
     */
    public static LoadedCore load(CoreArchive.CoreJar jar, ClassLoader parent, int expectedContract)
            throws CoreArchiveException {
        if (jar.contract() != expectedContract) {
            throw new CoreArchiveException("core " + jar.identity() + " was built for shell contract "
                    + jar.contract() + " and this shell implements " + expectedContract
                    + "; it can only be installed with a restart");
        }
        URLClassLoader loader;
        try {
            loader = new URLClassLoader(new URL[] {jar.path().toUri().toURL()}, parent);
        } catch (IOException badPath) {
            throw new CoreArchiveException("cannot open " + jar.path() + ": " + badPath.getMessage(),
                    badPath);
        }
        try {
            Class<?> type = Class.forName(jar.entryClass(), true, loader);
            if (type.getClassLoader() != loader) {
                throw new CoreArchiveException("the core entry point " + jar.entryClass()
                        + " resolved from outside the core jar; the build split is broken");
            }
            if (!HeimdallCore.class.isAssignableFrom(type)) {
                throw new CoreArchiveException("the core entry point " + jar.entryClass()
                        + " is not a HeimdallCore");
            }
            HeimdallCore core = (HeimdallCore) type.getConstructor().newInstance();
            int declared = core.contractVersion();
            if (declared != expectedContract) {
                throw new CoreArchiveException("core " + jar.identity() + " declares shell contract "
                        + declared + " in code and " + jar.contract() + " in its manifest; refusing "
                        + "a build that disagrees with itself");
            }
            return new LoadedCore(core, loader, jar.identity(), jar.path());
        } catch (CoreArchiveException refused) {
            closeQuietly(loader);
            throw refused;
        } catch (Throwable broken) {
            // Throwable: a LinkageError or ExceptionInInitializerError from the core's static
            // initialisers is the most likely way this fails, and both are Errors.
            closeQuietly(loader);
            throw new CoreArchiveException("could not construct core " + jar.identity() + ": "
                    + broken, broken);
        }
    }

    private static void closeQuietly(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException ignored) {
            // Already failing; the failure being reported is the useful one.
        }
    }
}
