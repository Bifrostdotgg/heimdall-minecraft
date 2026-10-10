package com.heimdall.shell.contract;

import java.nio.file.Path;

/**
 * A core the shell has extracted and checked, and whether it can be swapped in live.
 *
 * <p>The answer to "can this release go in without a restart?". A release whose core was built
 * against a different {@link ShellContract#VERSION} cannot: the updater still installs the whole
 * jar for the next restart (the pre-hot-swap behaviour), and says why. Plain values only, so a core
 * can hold one across the call that swaps it out.
 *
 * <p>Immutable.
 */
public final class StagedCore {

    private final Path path;
    private final CoreIdentity identity;
    private final int contract;
    private final String problem;

    public StagedCore(Path path, CoreIdentity identity, int contract, String problem) {
        this.path = path;
        this.identity = identity;
        this.contract = contract;
        this.problem = problem == null ? "" : problem;
    }

    /** A staging that failed outright: no core could be read at all. */
    public static StagedCore unusable(String problem) {
        return new StagedCore(null, null, -1, problem);
    }

    /** The extracted core jar, or {@code null} when nothing could be extracted. */
    public Path path() {
        return path;
    }

    /** The extracted core's identity, or {@code null} when nothing could be extracted. */
    public CoreIdentity identity() {
        return identity;
    }

    /** The contract the core was built against, or -1 when unknown. */
    public int contract() {
        return contract;
    }

    /** Why it cannot be swapped in live; empty when it can. One operator-facing sentence. */
    public String problem() {
        return problem;
    }

    /** Whether {@link ShellContext#swapTo} will accept it. */
    public boolean swappable() {
        return problem.isEmpty() && path != null && identity != null;
    }

    @Override
    public String toString() {
        return swappable() ? "staged core " + identity : "unswappable core: " + problem;
    }
}
