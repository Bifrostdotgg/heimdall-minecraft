package com.heimdall.core.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;

/**
 * A readable, stable-enough answer to "which machine is this install running on?".
 *
 * <p>Two tiers, in order of how much they can be trusted:
 *
 * <ul>
 *   <li>{@link Tier#PANEL} - {@value #PANEL_UUID_ENV} is set. Pterodactyl, Pelican and their forks
 *       export it into every server container, it is stable across restarts and reinstalls, and a
 *       copied server directory gets a different one. When it is present, nothing else is looked at.
 *   <li>{@link Tier#HOST} - the fallback: the host name plus the real path of the data directory.
 *       Good enough to notice a second copy on the same box, but a container hostname changes when
 *       the container is recreated, so a change at this tier is not proof of anything.
 * </ul>
 *
 * <p>The value is deliberately readable rather than hashed, because it is what gets shown to an
 * admin who has to decide whether this server moved or was copied. It is stored in
 * {@code bootstrap.yml}, which already holds the tunnel token, so a host name and a path there are
 * not a new secret. The {@link #digest()} is what goes over the wire.
 *
 * <p>The host name is read from {@code /etc/hostname}, then the {@code HOSTNAME} and
 * {@code COMPUTERNAME} environment variables, and then given up on. There is deliberately no DNS
 * lookup at the end of that list: {@code InetAddress.getLocalHost()} blocks on the resolver, this
 * runs on the thread that is enabling the plugin, and a box with a broken resolver would hold the
 * server's boot open for the timeout to learn a name it is about to call {@code unknown-host}
 * anyway.
 *
 * <p>Nothing here throws. Every source that can fail degrades to the next one, and the last one is
 * a literal.
 *
 * <p>Detection is not quite side-effect-free: it creates the data directory if it is missing,
 * before resolving its real path. On a first boot the directory does not exist yet, and
 * {@code toRealPath} on a missing path falls back to the path as written, so a fingerprint taken
 * before the directory was created would not match the one taken after it, and a server under a
 * symlinked mount would bind to a path that later resolves differently. The plugin creates that
 * directory moments later regardless, so this only moves it earlier.
 */
public final class InstanceFingerprint {

    /** How the fingerprint was arrived at, worst to best is {@link Tier#HOST} then {@link Tier#PANEL}. */
    public enum Tier {

        /** A game panel's per-server uuid. Survives restarts, differs for a copy. */
        PANEL,

        /** Host name plus data directory. A weaker signal, see the class javadoc. */
        HOST
    }

    /** The environment variable Pterodactyl-style panels set to the server's uuid. */
    public static final String PANEL_UUID_ENV = "P_SERVER_UUID";

    static final String UNKNOWN_HOST = "unknown-host";
    static final String UNKNOWN_PATH = "unknown-path";

    private static final Path ETC_HOSTNAME = Paths.get("/etc/hostname");

    private final Tier tier;
    private final String value;
    private final String digest;

    private InstanceFingerprint(Tier tier, String value) {
        this.tier = tier;
        this.value = value;
        this.digest = sha256Hex(value);
    }

    /** A panel-tier fingerprint for {@code uuid}. */
    public static InstanceFingerprint panel(String uuid) {
        return new InstanceFingerprint(Tier.PANEL, "panel:" + trimmed(uuid));
    }

    /** A host-tier fingerprint for a host name and an already-resolved path. */
    public static InstanceFingerprint host(String hostname, String path) {
        return new InstanceFingerprint(Tier.HOST, "host:" + trimmed(hostname) + "|" + trimmed(path));
    }

    /**
     * Works out this instance's fingerprint from the environment and the plugin's data directory.
     *
     * @param environment normally {@code System.getenv()}; may be {@code null}
     * @param dataDirectory the plugin's data directory; may be {@code null}
     */
    public static InstanceFingerprint detect(Map<String, String> environment, Path dataDirectory) {
        return detect(environment, dataDirectory, ETC_HOSTNAME);
    }

    /**
     * The seam {@link #detect(Map, Path)} is built on, so tests can drive the host name sources
     * without depending on the machine they run on.
     *
     * @param hostnameFile the file to read a host name from, normally {@code /etc/hostname}
     */
    static InstanceFingerprint detect(
            Map<String, String> environment, Path dataDirectory, Path hostnameFile) {
        String panel = trimmed(env(environment, PANEL_UUID_ENV));
        if (!panel.isEmpty()) {
            return panel(panel);
        }
        return host(hostname(environment, hostnameFile), realPath(dataDirectory));
    }

    /** Which tier the value came from. */
    public Tier tier() {
        return tier;
    }

    /** The readable value, for storage, logs and the admin command. Never {@code null} or blank. */
    public String value() {
        return value;
    }

    /** Lower-case SHA-256 hex of {@link #value()}, which is what the handshake carries. */
    public String digest() {
        return digest;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof InstanceFingerprint)) {
            return false;
        }
        InstanceFingerprint that = (InstanceFingerprint) other;
        return tier == that.tier && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tier, value);
    }

    @Override
    public String toString() {
        return value;
    }

    private static String hostname(Map<String, String> environment, Path hostnameFile) {
        String fromFile = trimmed(readFirstLine(hostnameFile));
        if (!fromFile.isEmpty()) {
            return fromFile;
        }
        String fromEnv = trimmed(env(environment, "HOSTNAME"));
        if (!fromEnv.isEmpty()) {
            return fromEnv;
        }
        String fromWindows = trimmed(env(environment, "COMPUTERNAME"));
        if (!fromWindows.isEmpty()) {
            return fromWindows;
        }
        // Deliberately the end of the list. See the class javadoc: a DNS lookup here would block
        // the thread that is enabling the plugin, and "unknown-host" costs a warning at worst,
        // because the path half of the value still tells two copies on one box apart.
        return UNKNOWN_HOST;
    }

    /**
     * Reads the first line of {@code file}, or returns empty for anything at all going wrong.
     *
     * <p>{@code /etc/hostname} does not exist on Windows and is not guaranteed readable inside a
     * container, and a fingerprint is not worth an exception either way.
     */
    private static String readFirstLine(Path file) {
        if (file == null) {
            return "";
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            String text = new String(bytes, StandardCharsets.UTF_8);
            int newline = text.indexOf('\n');
            return newline < 0 ? text : text.substring(0, newline);
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    /**
     * The data directory with symlinks resolved, so the same directory reached two ways fingerprints
     * the same. Falls back to the absolute normalised path when the real path cannot be taken.
     *
     * <p>The directory is created first, and that is not a convenience. {@code toRealPath()} fails
     * on a path that does not exist yet, so a data directory under a symlinked mount would take the
     * unresolved fallback on the very first boot and the resolved one on every boot after it: the
     * fingerprint would change by itself, once, on exactly the install that has just recorded it.
     * Creating it is also what the platform is about to do anyway, one line later.
     */
    private static String realPath(Path dataDirectory) {
        if (dataDirectory == null) {
            return UNKNOWN_PATH;
        }
        try {
            try {
                Files.createDirectories(dataDirectory);
            } catch (IOException | RuntimeException notCreated) {
                // Best effort. If it exists already this was a no-op, and if it cannot be created
                // the resolution below fails into the same fallback it would have anyway.
            }
            return dataDirectory.toRealPath().toString();
        } catch (IOException | RuntimeException notResolvable) {
            try {
                return dataDirectory.toAbsolutePath().normalize().toString();
            } catch (RuntimeException notAbsolute) {
                return dataDirectory.toString();
            }
        }
    }

    private static String env(Map<String, String> environment, String key) {
        if (environment == null) {
            return "";
        }
        try {
            String value = environment.get(key);
            return value == null ? "" : value;
        } catch (RuntimeException hostile) {
            return "";
        }
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("this JVM has no SHA-256", impossible);
        }
    }
}
