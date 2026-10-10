package com.heimdall.shell.hotswap;

import com.heimdall.shell.contract.CoreIdentity;
import com.heimdall.shell.contract.ShellContract;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * Reads core jars and release jars, and puts a core on disk where a classloader can open it.
 *
 * <h2>Two shapes of input</h2>
 *
 * <ul>
 *   <li><strong>A release jar</strong>: the single {@code heimdall-whitelist-<version>.jar} asset.
 *       The shell, with the core stored inside it at {@link ShellContract#EMBEDDED_CORE} and the
 *       core's version, hash and contract recorded next to it at
 *       {@link ShellContract#EMBEDDED_CORE_PROPERTIES} by the build.
 *   <li><strong>A core jar</strong>: the nested jar on its own, which is what an operator stages by
 *       hand and what this class writes out.
 * </ul>
 *
 * <h2>Every read goes through {@link JarFile}, never through a classloader</h2>
 *
 * <p>On Velocity and BungeeCord the updater replaces the running plugin jar in place. The shell's
 * own classloader still has the old file open, so asking it for a resource would hand back the old
 * core from a jar that no longer exists on disk. Opening the path afresh reads what is actually
 * there. The JDK's {@code JarURLConnection} cache is avoided for the same reason: it would pin an
 * open handle to a file this class is about to compare against.
 *
 * <h2>Files on disk are content-addressed</h2>
 *
 * <p>A core is written as {@code heimdall-core-<version>-<sha12>.jar}, never under a fixed name. A
 * live classloader holds its jar open; overwriting that file corrupts the running core on Linux and
 * fails outright on Windows. With the hash in the name, writing a different core never touches the
 * file a running one is using, and writing the same core again is a no-op.
 */
public final class CoreArchive {

    /**
     * Ceiling on a nested core, read into memory to be hashed. The real one is a few megabytes; this
     * is a bound against a hostile or corrupt jar, not a size budget.
     */
    static final long MAX_CORE_BYTES = 64L * 1024 * 1024;

    private static final String FILE_PREFIX = "heimdall-core-";

    private CoreArchive() {
    }

    /** A core jar on disk, described well enough to decide whether to load it. */
    public static final class CoreJar {

        private final Path path;
        private final CoreIdentity identity;
        private final int contract;
        private final String entryClass;

        CoreJar(Path path, CoreIdentity identity, int contract, String entryClass) {
            this.path = path;
            this.identity = identity;
            this.contract = contract;
            this.entryClass = entryClass;
        }

        public Path path() {
            return path;
        }

        public CoreIdentity identity() {
            return identity;
        }

        /** The {@link ShellContract#VERSION} the core was built against. */
        public int contract() {
            return contract;
        }

        /** The class its services entry names. */
        public String entryClass() {
            return entryClass;
        }

        @Override
        public String toString() {
            return identity + " at " + path.getFileName();
        }
    }

    /** The core a release jar carries, read and checked against what the build recorded. */
    public static final class Embedded {

        private final String version;
        private final String sha256;
        private final int contract;
        private final byte[] bytes;

        Embedded(String version, String sha256, int contract, byte[] bytes) {
            this.version = version;
            this.sha256 = sha256;
            this.contract = contract;
            this.bytes = bytes;
        }

        public String version() {
            return version;
        }

        public String sha256() {
            return sha256;
        }

        public int contract() {
            return contract;
        }

        byte[] bytes() {
            return bytes;
        }
    }

    /** Whether {@code jar} is a release jar, i.e. carries an embedded core. */
    public static boolean isRelease(Path jar) throws CoreArchiveException {
        JarFile file = open(jar);
        try {
            return file.getEntry(ShellContract.EMBEDDED_CORE) != null;
        } finally {
            closeQuietly(file);
        }
    }

    /**
     * Reads the embedded core out of a release jar and checks it against the recorded hash.
     *
     * <p>The recorded hash is the build's statement of what it nested, and the comparison is what
     * catches a release jar that was assembled wrongly or damaged after the download was verified.
     * It is not a substitute for verifying the download itself, which happens before this is ever
     * called: whoever can rewrite the outer jar can rewrite the properties next to the core too.
     */
    public static Embedded readEmbedded(Path releaseJar) throws CoreArchiveException {
        JarFile file = open(releaseJar);
        try {
            ZipEntry properties = file.getEntry(ShellContract.EMBEDDED_CORE_PROPERTIES);
            ZipEntry nested = file.getEntry(ShellContract.EMBEDDED_CORE);
            if (properties == null || nested == null) {
                throw new CoreArchiveException(releaseJar.getFileName() + " carries no embedded "
                        + "core; it is either not a Heimdall release or one older than hot-swap");
            }
            InputStream propertiesIn = file.getInputStream(properties);
            try {
                InputStream nestedIn = file.getInputStream(nested);
                try {
                    return readEmbedded(propertiesIn, nestedIn);
                } finally {
                    closeQuietly(nestedIn);
                }
            } finally {
                closeQuietly(propertiesIn);
            }
        } catch (IOException unreadable) {
            throw new CoreArchiveException("could not read " + releaseJar.getFileName() + ": "
                    + unreadable.getMessage(), unreadable);
        } finally {
            closeQuietly(file);
        }
    }

    /** {@link #readEmbedded(Path)} over two already-open streams. Neither is closed here. */
    public static Embedded readEmbedded(InputStream propertiesIn, InputStream nestedIn)
            throws CoreArchiveException {
        Properties recorded = new Properties();
        try {
            recorded.load(new InputStreamReader(propertiesIn, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new CoreArchiveException("the embedded core's properties are unreadable: "
                    + unreadable.getMessage(), unreadable);
        }
        String version = trimmed(recorded.getProperty(ShellContract.PROPERTY_VERSION));
        String sha256 = trimmed(recorded.getProperty(ShellContract.PROPERTY_SHA256));
        int contract = parseContract(recorded.getProperty(ShellContract.PROPERTY_CONTRACT));
        if (version.isEmpty() || !Sha256.isWellFormed(sha256) || contract < 1) {
            throw new CoreArchiveException("the embedded core's properties are incomplete (version '"
                    + version + "', sha256 '" + Sha256.display(sha256) + "', contract " + contract
                    + ")");
        }
        byte[] bytes;
        try {
            bytes = readBounded(nestedIn);
        } catch (IOException unreadable) {
            throw new CoreArchiveException("the embedded core is unreadable: "
                    + unreadable.getMessage(), unreadable);
        }
        String actual = Sha256.of(bytes);
        if (!actual.equals(sha256)) {
            throw new CoreArchiveException("the embedded core does not match the checksum recorded "
                    + "next to it (recorded " + sha256.substring(0, 12) + ", found "
                    + actual.substring(0, 12) + "); the release jar is damaged");
        }
        return new Embedded(version, sha256, contract, bytes);
    }

    /**
     * Writes an embedded core into {@code coreDir} under its content-addressed name.
     *
     * <p>Through a {@code .part} file and an atomic move, so a crash half-way leaves no truncated jar
     * under a name a later boot would trust. An existing file with the right content is reused.
     */
    public static Path materialise(Embedded core, Path coreDir) throws CoreArchiveException {
        Path target = coreDir.resolve(fileName(core.version(), core.sha256()));
        try {
            Files.createDirectories(coreDir);
            if (Files.isRegularFile(target) && core.sha256().equals(Sha256.of(target))) {
                return target;
            }
            Path part = coreDir.resolve(target.getFileName() + ".part");
            Files.write(part, core.bytes());
            moveIntoPlace(part, target);
            return target;
        } catch (IOException unwritable) {
            throw new CoreArchiveException("could not write the core to " + coreDir + ": "
                    + unwritable.getMessage(), unwritable);
        }
    }

    /**
     * Copies a bare core jar into {@code coreDir} under its content-addressed name, so loading it
     * never holds a handle on the file an operator dropped in (and may replace again).
     */
    public static Path adopt(Path coreJar, CoreIdentity identity, Path coreDir)
            throws CoreArchiveException {
        Path target = coreDir.resolve(fileName(identity.version(), identity.sha256()));
        try {
            Files.createDirectories(coreDir);
            if (Files.isRegularFile(target) && identity.sha256().equals(Sha256.of(target))) {
                return target;
            }
            Path part = coreDir.resolve(target.getFileName() + ".part");
            Files.copy(coreJar, part, StandardCopyOption.REPLACE_EXISTING);
            String copied = Sha256.of(part);
            if (!copied.equals(identity.sha256())) {
                Files.deleteIfExists(part);
                throw new CoreArchiveException(coreJar.getFileName() + " changed while it was being "
                        + "copied; stage it again");
            }
            moveIntoPlace(part, target);
            return target;
        } catch (IOException unwritable) {
            throw new CoreArchiveException("could not copy the core into " + coreDir + ": "
                    + unwritable.getMessage(), unwritable);
        }
    }

    /**
     * Reads what a core jar says about itself: its manifest attributes, its services entry and its
     * hash.
     *
     * <p>Nothing is loaded. A jar that is not a core (no contract attribute, no entry) is refused
     * here, with the reason, rather than by a {@code ClassNotFoundException} later.
     */
    public static CoreJar describe(Path coreJar) throws CoreArchiveException {
        String sha256;
        try {
            sha256 = Sha256.of(coreJar);
        } catch (IOException unreadable) {
            throw new CoreArchiveException("could not read " + coreJar.getFileName() + ": "
                    + unreadable.getMessage(), unreadable);
        }
        JarFile file = open(coreJar);
        try {
            Manifest manifest = file.getManifest();
            Attributes main = manifest == null ? null : manifest.getMainAttributes();
            int contract = parseContract(main == null
                    ? null : main.getValue(ShellContract.MANIFEST_CONTRACT));
            if (contract < 1) {
                throw new CoreArchiveException(coreJar.getFileName() + " is not a Heimdall core: "
                        + "its manifest declares no " + ShellContract.MANIFEST_CONTRACT);
            }
            String version = trimmed(main.getValue(ShellContract.MANIFEST_CORE_VERSION));
            String entry = serviceEntry(file);
            if (entry.isEmpty()) {
                throw new CoreArchiveException(coreJar.getFileName() + " is not a Heimdall core: it "
                        + "names no entry point in " + ShellContract.SERVICE_ENTRY);
            }
            return new CoreJar(coreJar, new CoreIdentity(version, sha256), contract, entry);
        } catch (IOException unreadable) {
            throw new CoreArchiveException("could not read " + coreJar.getFileName() + ": "
                    + unreadable.getMessage(), unreadable);
        } finally {
            closeQuietly(file);
        }
    }

    /**
     * Deletes every {@code heimdall-core-*.jar} in {@code coreDir} except {@code keep}.
     *
     * <p>Only ever called at server start, when no core classloader has a file open. Best effort: a
     * file that cannot be deleted is left for the next start.
     *
     * @return how many files were removed
     */
    public static int prune(Path coreDir, Set<Path> keep, ShellLog log) {
        if (!Files.isDirectory(coreDir)) {
            return 0;
        }
        int removed = 0;
        try {
            DirectoryStream<Path> files = Files.newDirectoryStream(coreDir, FILE_PREFIX + "*");
            try {
                for (Path file : files) {
                    if (keep.contains(file.toAbsolutePath().normalize())) {
                        continue;
                    }
                    try {
                        if (Files.deleteIfExists(file)) {
                            removed++;
                        }
                    } catch (IOException busy) {
                        log.debug("could not remove the unused core " + file.getFileName() + ": "
                                + busy.getMessage());
                    }
                }
            } finally {
                files.close();
            }
        } catch (IOException unreadable) {
            log.debug("could not list " + coreDir + ": " + unreadable.getMessage());
        }
        return removed;
    }

    /** The content-addressed file name for a core. */
    static String fileName(String version, String sha256) {
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < version.length() && safe.length() < 48; i++) {
            char c = version.charAt(i);
            boolean plain = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || c == '.' || c == '-' || c == '_' || c == '+';
            safe.append(plain ? c : '_');
        }
        String shortSha = sha256.length() > 12 ? sha256.substring(0, 12) : sha256;
        return FILE_PREFIX + (safe.length() == 0 ? "unknown" : safe.toString()) + "-" + shortSha
                + ".jar";
    }

    private static String serviceEntry(JarFile file) throws IOException {
        ZipEntry entry = file.getEntry(ShellContract.SERVICE_ENTRY);
        if (entry == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(entry), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                int comment = line.indexOf('#');
                String trimmed = (comment >= 0 ? line.substring(0, comment) : line).trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
            return "";
        } finally {
            reader.close();
        }
    }

    private static void moveIntoPlace(Path part, Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException crossDevice) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4 * 1024 * 1024);
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_CORE_BYTES) {
                throw new IOException("the embedded core is larger than " + MAX_CORE_BYTES
                        + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static JarFile open(Path jar) throws CoreArchiveException {
        if (jar == null || !Files.isRegularFile(jar)) {
            throw new CoreArchiveException((jar == null ? "no file" : jar.toString())
                    + " does not exist");
        }
        try {
            return new JarFile(jar.toFile());
        } catch (IOException notAJar) {
            throw new CoreArchiveException(jar.getFileName() + " is not a readable jar: "
                    + notAJar.getMessage(), notAJar);
        }
    }

    static int parseContract(String raw) {
        if (raw == null) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException malformed) {
            return -1;
        }
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // A read-only handle. Nothing was written through it, so a failed close loses nothing.
        }
    }
}
