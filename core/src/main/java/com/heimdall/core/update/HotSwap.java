package com.heimdall.core.update;

import java.nio.file.Path;

/**
 * The updater's view of the hot-swap shell: can a downloaded release go in live, and if so, go.
 *
 * <p>An interface rather than the shell context itself so {@link UpdateService} stays testable with
 * a two-method fake, the same reason {@link ReleaseSource} and {@link UpdateInstaller} exist. The
 * production adapter is {@code com.heimdall.platform.common.ShellHotSwap} (departure D87).
 */
public interface HotSwap {

    /** No shell: every update is installed for the next restart, as before hot-swap existed. */
    HotSwap NONE = new HotSwap() {
        @Override
        public Staged stage(Path releaseJar) {
            return Staged.refused("this build cannot swap its core live");
        }
    };

    /**
     * Extracts and checks the core inside a downloaded, <strong>verified</strong> release jar.
     * Blocking; never on a server thread. Never throws.
     */
    Staged stage(Path releaseJar);

    /** A staged core, and whether it can go in live. */
    abstract class Staged {

        /** Whether {@link #swap} can be called. */
        public abstract boolean swappable();

        /** Why it cannot be swapped live; empty when it can. */
        public abstract String problem();

        /** The staged core's version, or empty when unknown. */
        public abstract String version();

        /**
         * Starts the live swap, which stops the core this is running in. Everything the caller
         * still wants to say must be said first.
         *
         * @param audience a platform command sender to report the outcome to, or {@code null}
         * @return whether the swap was started
         */
        public abstract boolean swap(Object audience);

        /** A core that cannot be swapped in, for {@code reason}. */
        public static Staged refused(final String reason) {
            return new Staged() {
                @Override
                public boolean swappable() {
                    return false;
                }

                @Override
                public String problem() {
                    return reason;
                }

                @Override
                public String version() {
                    return "";
                }

                @Override
                public boolean swap(Object audience) {
                    return false;
                }
            };
        }
    }
}
