package com.heimdall.shell.hotswap;

import com.heimdall.shell.contract.CoreIdentity;
import com.heimdall.shell.contract.HeimdallCore;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * A core instance, the classloader it came from, and which jar that was.
 *
 * <p>Constructed but not started: {@link CoreLoader} hands one of these back, and the shell decides
 * what to do with it. The loader is closed by {@link #closeLoader()}, which the shell only calls once
 * nothing can still need a class from it (see {@code ShellHost}'s grace period).
 */
public final class LoadedCore {

    private final HeimdallCore core;
    private final URLClassLoader loader;
    private final CoreIdentity identity;
    private final Path jar;

    LoadedCore(HeimdallCore core, URLClassLoader loader, CoreIdentity identity, Path jar) {
        this.core = core;
        this.loader = loader;
        this.identity = identity;
        this.jar = jar;
    }

    public HeimdallCore core() {
        return core;
    }

    public CoreIdentity identity() {
        return identity;
    }

    /** The content-addressed jar it was loaded from. A rollback reloads from here. */
    public Path jar() {
        return jar;
    }

    /** The loader, for the sweep's "does this object belong to that core?" question. */
    public ClassLoader loader() {
        return loader;
    }

    /**
     * Whether {@code type} was defined by this core's loader or by a loader created under it.
     *
     * <p>"Under it" matters on Paper 1.16-era servers, whose generated event executors live in a
     * child classloader of the listener's own loader: such an executor belongs to this core just as
     * much as the listener does.
     */
    public boolean owns(Class<?> type) {
        if (type == null) {
            return false;
        }
        ClassLoader cursor = type.getClassLoader();
        while (cursor != null) {
            if (cursor == loader) {
                return true;
            }
            cursor = cursor.getParent();
        }
        return false;
    }

    /** Closes the loader. Idempotent; a failure is reported to {@code log} and otherwise ignored. */
    public void closeLoader(ShellLog log) {
        try {
            loader.close();
        } catch (IOException failed) {
            log.debug("closing the classloader of core " + identity + " failed: "
                    + failed.getMessage());
        }
    }
}
