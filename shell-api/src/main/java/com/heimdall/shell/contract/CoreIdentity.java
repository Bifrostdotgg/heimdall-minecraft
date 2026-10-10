package com.heimdall.shell.contract;

/**
 * Which core is running: its version and the SHA-256 of the jar it was loaded from.
 *
 * <p>The hash is the identity that matters. Two builds can carry the same version string (every
 * snapshot does), and "is the core I asked for the one that is running?" is only answerable by
 * content. {@code /hd status} shows both.
 *
 * <p>Immutable, and made of plain strings so it can be handed to any generation.
 */
public final class CoreIdentity {

    private final String version;
    private final String sha256;

    public CoreIdentity(String version, String sha256) {
        this.version = version == null || version.trim().isEmpty() ? "unknown" : version.trim();
        this.sha256 = sha256 == null ? "" : sha256.trim();
    }

    /** The core's version, e.g. {@code 3.1.0}; {@code unknown} when the jar did not say. */
    public String version() {
        return version;
    }

    /** The core jar's SHA-256 as 64 lowercase hex characters, or empty when unknown. */
    public String sha256() {
        return sha256;
    }

    /** The first twelve hex characters of {@link #sha256()}, for a log line. */
    public String shortSha() {
        return sha256.length() <= 12 ? sha256 : sha256.substring(0, 12);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CoreIdentity)) {
            return false;
        }
        CoreIdentity that = (CoreIdentity) other;
        return version.equals(that.version) && sha256.equals(that.sha256);
    }

    @Override
    public int hashCode() {
        return 31 * version.hashCode() + sha256.hashCode();
    }

    @Override
    public String toString() {
        return version + (sha256.isEmpty() ? "" : " (sha256 " + shortSha() + ")");
    }
}
