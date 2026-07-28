package com.heimdall.core.admin;

/**
 * The self-updater's operator surface: {@code /hd version} and {@code /hd update}.
 *
 * <p>An interface rather than a direct call into {@code com.heimdall.core.update} for one reason
 * that is not architectural purity — <strong>installing a jar is platform work</strong>. Paper drops
 * it into {@code plugins/update/} and applies it on restart; Velocity has no such folder and instead
 * exposes the running jar's own path, which is replaced in place with a data-directory fallback for
 * a file Windows has locked. The updater proper is platform-free and the installer behind it is not,
 * so the admin tree talks to the pair through one seam and neither half leaks into the other.
 *
 * <p>{@link #NONE} is what a build with no updater answers, and it is honest rather than silent:
 * {@code /hd update} says the feature is not available instead of appearing to do something.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #checkNow()} and {@link #updateNow()} <strong>block</strong> — one on an HTTP round trip,
 * the other on that plus a jar download of up to 50 MB. Both belong on {@code heimdall-io}, never on
 * a server thread. Everything else is a volatile read.
 */
public interface UpdateAdmin {

    /** What a build with no self-updater answers. */
    UpdateAdmin NONE = new UpdateAdmin() {

        @Override
        public boolean isSupported() {
            return false;
        }

        @Override
        public String currentVersion() {
            return "";
        }

        @Override
        public boolean checkNow() {
            return false;
        }

        @Override
        public String updateNow() {
            return "this build has no self-updater";
        }

        @Override
        public boolean isUpdateAvailable() {
            return false;
        }

        @Override
        public String latestVersion() {
            return "";
        }
    };

    /** Whether an updater is wired up at all. */
    boolean isSupported();

    /** The version running now. */
    String currentVersion();

    /**
     * Asks the bot for the latest release and republishes the state. Blocking; never throws.
     *
     * @return whether something newer than {@link #currentVersion()} is published
     */
    boolean checkNow();

    /**
     * Checks and, if there is something newer, downloads and installs it. Blocking; never throws.
     *
     * @return one operator-facing sentence saying what happened, including the restart it needs
     */
    String updateNow();

    /** Whether the last successful check found something newer. */
    boolean isUpdateAvailable();

    /** The newest published version, or {@code ""} before a successful check. */
    String latestVersion();
}
