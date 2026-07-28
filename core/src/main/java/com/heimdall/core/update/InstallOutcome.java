package com.heimdall.core.update;

import java.nio.file.Path;
import java.util.Objects;

/**
 * What happened when an update was installed, in a form a command handler can print unchanged.
 *
 * <h2>Why the message is part of the value</h2>
 *
 * <p>The two shipped strategies succeed in materially different ways, and an operator has to be
 * told which one happened, because what they do next differs:
 *
 * <ul>
 *   <li><strong>Paper</strong> drops the jar into {@code plugins/update/} under the running
 *       plugin's own file name. The server installs it on the next start; there is nothing to move.
 *   <li><strong>Velocity</strong> replaces the running jar in place. On Linux the JVM keeps the old
 *       inode open until the process exits, so this is safe — but on Windows the file is locked and
 *       the rename fails, and the fallback writes the jar into the plugin's data directory instead.
 *       That outcome is a <em>success</em>, and it needs the operator to move a file by hand.
 * </ul>
 *
 * <p>v2 returned a bare {@code String} from its Velocity {@code installLatestUpdate()} and returned
 * nothing at all from Paper's, so Paper's caller printed a fixed line that was true only by
 * coincidence. Making the message part of the outcome puts the sentence next to the branch that
 * knows which case it is.
 *
 * <p><strong>Neither strategy applies until a restart.</strong> No outcome here means the new
 * version is running; every success message says so, and a caller must not imply otherwise.
 *
 * <p>Named factories rather than a constructor, per departure D21: {@code boolean} + {@code String}
 * + nullable {@code Path} is exactly the signature a positional call gets wrong and still compiles.
 *
 * <p><strong>Immutable and thread-safe.</strong> A value; owns nothing. {@link #target()} is a
 * {@link Path}, which is itself immutable, so there is nothing to defensively copy.
 */
public final class InstallOutcome {

    private final boolean installed;
    private final String message;
    private final Path target;

    private InstallOutcome(boolean installed, String message, Path target) {
        this.installed = installed;
        this.message = message == null ? "" : message;
        this.target = target;
    }

    /**
     * The new jar is on disk and will be picked up on the next restart.
     *
     * @param target where it landed; may be {@code null} if the strategy has no single path
     * @param message one operator-facing sentence, e.g. "Installed 3.1.0 in place — restart the
     *     proxy to apply."
     */
    public static InstallOutcome installed(Path target, String message) {
        return new InstallOutcome(true, message, target);
    }

    /** Nothing newer than what is running, so nothing was downloaded. */
    public static InstallOutcome upToDate(String message) {
        return new InstallOutcome(false, message, null);
    }

    /**
     * The install did not happen.
     *
     * <p>A returned value rather than a thrown exception because every caller is a command handler
     * or a tunnel reply that has to say something either way, and none of them can act on a stack
     * trace. {@link UpdateService#updateNow()} is documented never to throw for the same reason.
     */
    public static InstallOutcome failed(String message) {
        return new InstallOutcome(false, message, null);
    }

    /** Whether a new jar is now staged. False for both {@link #upToDate} and {@link #failed}. */
    public boolean installed() {
        return installed;
    }

    /** One operator-facing sentence. Never {@code null}; may be empty. */
    public String message() {
        return message;
    }

    /** Where the jar landed, or {@code null} when nothing was written. */
    public Path target() {
        return target;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof InstallOutcome)) {
            return false;
        }
        InstallOutcome that = (InstallOutcome) other;
        return installed == that.installed
                && message.equals(that.message)
                && Objects.equals(target, that.target);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Boolean.valueOf(installed), message, target);
    }

    @Override
    public String toString() {
        return "InstallOutcome{installed=" + installed + ", message='" + message + "'}";
    }
}
