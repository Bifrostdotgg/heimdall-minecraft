package com.heimdall.platform.bukkit.itemimage;

import com.heimdall.core.items.Snbt;
import com.heimdall.core.log.HeimdallLogger;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The vanilla client's item textures, models, fonts and English names, fetched from Mojang and kept
 * in {@code <plugin data>/cache/assets/<version>/}.
 *
 * <h2>Why fetched, and never shipped</h2>
 *
 * <p>Mojang's assets are not ours to redistribute, so the jar carries none of them. The server
 * downloads the matching client jar from Mojang's own piston servers, the same place every launcher
 * gets it, keeps the few thousand files a tooltip needs, and deletes the jar.
 *
 * <h2>Verified, and never half-used</h2>
 *
 * <ul>
 *   <li>The version document is checked against the SHA-1 the manifest lists for it, and the client
 *       jar against the SHA-1 and size the version document lists. A mismatch is an error, not a
 *       warning: an asset cache built from bytes nobody vouched for is not one this plugin uses.
 *   <li>Extraction writes into a temporary directory, writes a marker file holding the jar's SHA-1
 *       last, and only then renames the directory into place. A directory without the marker is a
 *       crash mid-extraction and is deleted, never read.
 *   <li>Every read is capped: manifest and version document sizes, the jar's declared size, the
 *       total extracted, the entry count. Zip entry names are checked so none can escape the
 *       directory.
 * </ul>
 *
 * <h2>Versions</h2>
 *
 * <p>The server's own version ({@code 1.21.5}, or a year version like {@code 26.1}) is looked up in
 * the manifest. A version the manifest does not list (a server built ahead of the manifest, a fork
 * with an odd version string) uses the nearest older release, which for icons and fonts is almost
 * always right and never worse than placeholders.
 *
 * <p>Blocking: called on the asset thread only. Not thread-safe; that thread is single.
 */
final class VanillaAssets {

    static final String MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /** The file whose presence (and content) marks a cache directory complete. */
    static final String MARKER = ".heimdall-complete";

    static final long MAX_MANIFEST_BYTES = 8L * 1024 * 1024;
    static final long MAX_VERSION_BYTES = 4L * 1024 * 1024;
    static final long MAX_CLIENT_JAR_BYTES = 256L * 1024 * 1024;
    static final long MAX_EXTRACTED_BYTES = 512L * 1024 * 1024;
    static final int MAX_EXTRACTED_ENTRIES = 100_000;

    /** Leftover temporary files younger than this may belong to another live server; left alone. */
    static final long LEFTOVER_AGE_MS = 10L * 60 * 1000;

    private final HeimdallLogger logger;
    private final HttpSource http;
    private final Path root;

    /**
     * Tags this instance's temporary files, so two servers sharing a plugin folder (or a quick
     * restart overlapping the old process) never mistake each other's in-flight files for leftovers.
     */
    private final String instance = Long.toHexString(new java.security.SecureRandom().nextLong());

    VanillaAssets(HeimdallLogger logger, HttpSource http, Path root) {
        this.logger = logger;
        this.http = http;
        this.root = root;
    }

    /**
     * The complete cache for {@code version}, or {@code null} if there is none. Never touches the
     * network. A directory without a valid marker is not a cache and is not returned.
     */
    Path cached(String version) {
        Path dir = root.resolve(safeName(version));
        return isComplete(dir) ? dir : null;
    }

    /**
     * The cache for {@code version}, downloading and extracting it first if needed.
     *
     * @throws IOException on any network, verification or extraction failure; nothing partial is
     *     left behind to be mistaken for a cache
     */
    Path ensure(String version) throws IOException {
        Path dir = root.resolve(safeName(version));
        if (isComplete(dir)) {
            return dir;
        }
        Files.createDirectories(root);
        if (Files.exists(dir)) {
            // No marker: a previous extraction died part-way. Never read; start again.
            deleteTree(dir);
        }

        Map<String, Object> manifest = json(http.get(MANIFEST_URL, MAX_MANIFEST_BYTES));
        Map<String, Object> entry = pickVersion(manifest, version);
        if (entry == null) {
            throw new AssetException("no release at or below " + version + " in Mojang's manifest");
        }
        String resolvedId = Snbt.asString(entry.get("id"));
        String versionUrl = Snbt.asString(entry.get("url"));
        if (versionUrl == null) {
            throw new AssetException("the manifest entry for " + resolvedId + " has no url");
        }
        byte[] versionBytes = http.get(versionUrl, MAX_VERSION_BYTES);
        String expectedVersionSha1 = Snbt.asString(entry.get("sha1"));
        // Fail closed: a manifest entry that vouches for nothing is not a chain this cache trusts.
        if (expectedVersionSha1 == null
                || !expectedVersionSha1.equalsIgnoreCase(sha1(versionBytes))) {
            throw new AssetException("the version document for " + resolvedId
                    + " does not match the manifest's SHA-1");
        }
        Map<String, Object> client = Snbt.asMap(
                at(json(versionBytes), "downloads", "client"));
        if (client == null) {
            throw new AssetException("the version document for " + resolvedId + " has no client");
        }
        String jarUrl = Snbt.asString(client.get("url"));
        String jarSha1 = Snbt.asString(client.get("sha1"));
        Double declared = Snbt.asDouble(client.get("size"), null);
        if (jarUrl == null || jarSha1 == null || declared == null) {
            throw new AssetException("the client download for " + resolvedId + " is incomplete");
        }
        long size = declared.longValue();
        if (size <= 0 || size > MAX_CLIENT_JAR_BYTES) {
            throw new AssetException("the client jar for " + resolvedId + " declares " + size
                    + " bytes, outside the accepted range");
        }

        String stamp = Long.toHexString(System.nanoTime());
        Path jar = root.resolve(".download-" + instance + "-" + safeName(version) + "-" + stamp
                + ".jar");
        Path staging = root.resolve(".staging-" + instance + "-" + safeName(version) + "-" + stamp);
        try {
            long written = http.download(jarUrl, jar, size);
            if (written != size) {
                throw new AssetException("the client jar for " + resolvedId + " is " + written
                        + " bytes, the version document says " + size);
            }
            if (!jarSha1.equalsIgnoreCase(sha1(jar))) {
                throw new AssetException("the client jar for " + resolvedId
                        + " does not match its SHA-1");
            }
            int files = extract(jar, staging);
            // The marker is written LAST, into the staging directory, so a directory carrying it
            // is complete by construction.
            Files.write(staging.resolve(MARKER),
                    (jarSha1.toLowerCase(Locale.ROOT) + " " + resolvedId + "\n")
                            .getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(staging, dir, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException raced) {
                if (isComplete(dir)) {
                    // Another process (a second server on the same folder) finished first.
                    return dir;
                }
                throw raced;
            }
            final String shown = resolvedId;
            final int count = files;
            logger.info("item images: vanilla " + shown + " assets ready (" + count + " files)");
            return dir;
        } finally {
            Files.deleteIfExists(jar);
            if (Files.exists(staging)) {
                deleteTree(staging);
            }
        }
    }

    /** Whether {@code dir} holds a marker naming a 40-character SHA-1. */
    static boolean isComplete(Path dir) {
        Path marker = dir.resolve(MARKER);
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        try {
            String text = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
            String sha = text.split("\\s+")[0];
            return sha.length() == 40 && sha.matches("[0-9a-fA-F]{40}");
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * The manifest entry for {@code version}, or the nearest older release; {@code null} if neither.
     * Package-private for the version-selection tests.
     */
    static Map<String, Object> pickVersion(Map<String, Object> manifest, String version) {
        List<Object> versions = Snbt.asList(manifest == null ? null : manifest.get("versions"));
        if (versions == null) {
            return null;
        }
        int[] wanted = parseVersion(version);
        Map<String, Object> best = null;
        int[] bestParsed = null;
        for (Object element : versions) {
            Map<String, Object> entry = Snbt.asMap(element);
            if (entry == null) {
                continue;
            }
            String id = Snbt.asString(entry.get("id"));
            if (id == null) {
                continue;
            }
            if (id.equals(version)) {
                return entry;
            }
            if (!"release".equals(Snbt.asString(entry.get("type")))) {
                continue;
            }
            int[] parsed = parseVersion(id);
            if (parsed == null || wanted == null || compare(parsed, wanted) > 0) {
                continue;
            }
            if (bestParsed == null || compare(parsed, bestParsed) > 0) {
                best = entry;
                bestParsed = parsed;
            }
        }
        return best;
    }

    /** {@code 1.21.5} to {@code [1, 21, 5]}, {@code 26.1} to {@code [26, 1, 0]}; else {@code null}. */
    static int[] parseVersion(String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.trim().split("\\.");
        if (parts.length < 2 || parts.length > 3) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("\\d{1,4}")) {
                return null;
            }
            out[i] = Integer.parseInt(parts[i]);
        }
        return out;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return a[i] < b[i] ? -1 : 1;
            }
        }
        return 0;
    }

    /**
     * Whether a client-jar entry is one a tooltip can use. Everything else (sounds, shaders, entity
     * textures, the game's classes) is never written to disk.
     */
    static boolean wanted(String name) {
        if ("version.json".equals(name)) {
            return true;
        }
        if (!name.startsWith("assets/minecraft/") || name.endsWith("/")) {
            return false;
        }
        String rest = name.substring("assets/minecraft/".length());
        String lower = rest.toLowerCase(Locale.ROOT);
        return rest.startsWith("font/")
                || rest.startsWith("textures/font/")
                || rest.startsWith("textures/item/")
                || rest.startsWith("textures/items/")
                || rest.startsWith("textures/block/")
                || rest.startsWith("textures/blocks/")
                || rest.startsWith("models/")
                || rest.startsWith("items/")
                || rest.startsWith("textures/misc/enchanted")
                || rest.startsWith("textures/gui/sprites/tooltip/")
                || lower.startsWith("lang/en_us.");
    }

    /** Extracts the wanted entries of {@code jar} into {@code target}; returns how many. */
    static int extract(Path jar, Path target) throws IOException {
        return extract(jar, target, MAX_EXTRACTED_ENTRIES, MAX_EXTRACTED_BYTES);
    }

    /** {@link #extract(Path, Path)} with explicit caps, for the cap tests. */
    static int extract(Path jar, Path target, int maxEntries, long maxBytes) throws IOException {
        Files.createDirectories(target);
        Path base = target.toAbsolutePath().normalize();
        int count = 0;
        long total = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !wanted(name)) {
                    continue;
                }
                Path out = base.resolve(name).normalize();
                if (!out.startsWith(base)) {
                    throw new AssetException("a client jar entry points outside the cache");
                }
                if (++count > maxEntries) {
                    throw new AssetException("more than " + maxEntries + " entries");
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(entry);
                        OutputStream os = Files.newOutputStream(out)) {
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        total += read;
                        if (total > maxBytes) {
                            throw new AssetException("extracted assets exceed " + maxBytes
                                    + " bytes");
                        }
                        os.write(buffer, 0, read);
                    }
                }
            }
        }
        return count;
    }

    /** The {@code resource} pack format the cached client declares, or -1 if it does not say. */
    static int packFormat(Path vanillaDir) {
        if (vanillaDir == null) {
            return -1;
        }
        Path file = vanillaDir.resolve("version.json");
        if (!Files.isRegularFile(file)) {
            return -1;
        }
        try {
            Object version = Snbt.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            Object pack = Snbt.asMap(version) == null ? null : Snbt.asMap(version).get("pack_version");
            Map<String, Object> split = Snbt.asMap(pack);
            Integer format = Snbt.asInt(split != null ? split.get("resource") : pack, null);
            if (format == null && split != null) {
                // 1.21.9+ writes {resource_major, resource_minor}.
                format = Snbt.asInt(split.get("resource_major"), null);
            }
            return format == null ? -1 : format;
        } catch (IOException | Snbt.SyntaxException unreadable) {
            return -1;
        }
    }

    static Map<String, Object> json(byte[] bytes) throws IOException {
        try {
            Map<String, Object> map = Snbt.asMap(Snbt.parse(new String(bytes, StandardCharsets.UTF_8),
                    (int) Math.min(Integer.MAX_VALUE, MAX_MANIFEST_BYTES * 2)));
            if (map == null) {
                throw new AssetException("expected a JSON object");
            }
            return map;
        } catch (Snbt.SyntaxException malformed) {
            throw new AssetException("malformed JSON: " + malformed.getMessage());
        }
    }

    private static Object at(Map<String, Object> map, String... keys) {
        Object current = map;
        for (String key : keys) {
            Map<String, Object> m = Snbt.asMap(current);
            if (m == null) {
                return null;
            }
            current = m.get(key);
        }
        return current;
    }

    static String sha1(byte[] bytes) {
        MessageDigest digest = sha1Digest();
        return hex(digest.digest(bytes));
    }

    static String sha1(Path file) throws IOException {
        MessageDigest digest = sha1Digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException impossible) {
            // Every Java platform is required to provide SHA-1.
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /** A version string as a directory name: anything outside {@code [A-Za-z0-9._-]} becomes '_'. */
    static String safeName(String version) {
        String v = version == null || version.isEmpty() ? "unknown" : version;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < v.length() && i < 64; i++) {
            char c = v.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        String name = out.toString();
        return name.startsWith(".") ? "_" + name : name;
    }

    /**
     * Removes staging directories and jars a crashed run left behind: only ones older than
     * {@link #LEFTOVER_AGE_MS}, because a younger one may be another live process mid-download.
     */
    void sweepLeftovers() {
        if (!Files.isDirectory(root)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - LEFTOVER_AGE_MS;
        List<Path> leftovers = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if ((name.startsWith(".staging-") || name.startsWith(".download-"))
                        && Files.getLastModifiedTime(child).toMillis() < cutoff) {
                    leftovers.add(child);
                }
            }
        } catch (IOException ignored) {
            return;
        }
        for (Path leftover : leftovers) {
            try {
                deleteTree(leftover);
            } catch (IOException ignored) {
                // Best effort; the next sweep tries again.
            }
        }
    }

    /**
     * Deletes every complete version cache other than {@code current}: after an upgrade the old
     * version's assets are dead weight. Incomplete directories are left to {@link #ensure}, which
     * rebuilds or deletes them; dot-prefixed temporaries to {@link #sweepLeftovers}.
     *
     * @return how many were removed
     */
    int pruneOthers(Path current) {
        if (current == null || !Files.isDirectory(root)) {
            return 0;
        }
        List<Path> stale = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                if (!child.getFileName().toString().startsWith(".") && Files.isDirectory(child)
                        && !child.equals(current) && isComplete(child)) {
                    stale.add(child);
                }
            }
        } catch (IOException ignored) {
            return 0;
        }
        int removed = 0;
        for (Path dir : stale) {
            try {
                deleteTree(dir);
                removed++;
            } catch (IOException ignored) {
                // Best effort; tried again on the next prepare.
            }
        }
        return removed;
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        if (!Files.isDirectory(dir)) {
            Files.deleteIfExists(dir);
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException failed)
                    throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
