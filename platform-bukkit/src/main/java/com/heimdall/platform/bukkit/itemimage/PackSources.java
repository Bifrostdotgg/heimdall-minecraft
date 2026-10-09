package com.heimdall.platform.bukkit.itemimage;

import com.heimdall.core.log.HeimdallLogger;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Where the resource packs a server's players see live, in the order they win.
 *
 * <ol>
 *   <li>The operator's own folder ({@code pack-folder} in {@code item-images.yml}, default
 *       {@code plugins/Heimdall/item-images/packs/}): every zip, and every directory that looks like
 *       a pack, by name.
 *   <li>The pack {@code server.properties} sends ({@code resource-pack}), downloaded into the cache
 *       and checked against {@code resource-pack-sha1} when one is set.
 *   <li>ItemsAdder's generated pack, {@code plugins/ItemsAdder/output/generated.zip}, or its
 *       {@code output_uncompressed/} folder when the zip will not open (ItemsAdder can protect it).
 *   <li>Nexo's and Oraxen's built packs: any zip (or unpacked pack) under {@code plugins/Nexo/pack/}
 *       and {@code plugins/Oraxen/pack/}.
 *   <li>Vanilla, last.
 * </ol>
 *
 * <p>A pack zip is never opened where it lies. ItemsAdder, Nexo and Oraxen rewrite their zips when
 * they rebuild, and an open handle (on Windows, a lock) on their file would get in their way for as
 * long as the stack lives. So each zip is copied into {@code cache/packs/open/} once per change, on
 * the asset thread ({@link #privateCopy}), and the copy is what is opened; a pack whose copy is not
 * ready yet (or failed) is simply left out of the stack and its fingerprint, so the next rescan
 * picks it up. The server pack is already the cache's own file, which nothing else writes, and is
 * opened directly. Copies no longer in use are deleted when the stack is rebuilt.
 *
 * <p>Discovery is a handful of {@code stat} calls, cheap enough to repeat every few seconds; the
 * {@link #fingerprint} of what it found (paths, sizes, modification times) is what decides whether
 * the stack has to be rebuilt, so a pack that ItemsAdder regenerates is picked up without a restart
 * and without re-reading anything per message.
 */
final class PackSources {

    /** Largest server resource pack downloaded, and largest pack zip copied. */
    static final long MAX_SERVER_PACK_BYTES = 250L * 1024 * 1024;

    /** How long a server pack with no {@code resource-pack-sha1} is trusted before it is re-fetched. */
    static final long UNHASHED_PACK_MAX_AGE_MS = 24L * 60 * 60 * 1000;

    private final String instance = Long.toHexString(new java.security.SecureRandom().nextLong());

    private final HeimdallLogger logger;
    private final Path serverRoot;
    private final Path pluginsDir;
    private final Path cacheDir;

    /** A pack on disk and whether it is a zip or a directory. */
    static final class Candidate {

        final Path path;
        final boolean zip;
        final String label;
        /** What is actually opened: the private copy of a pack zip, else {@link #path}. */
        final Path openPath;

        Candidate(Path path, boolean zip, String label) {
            this(path, zip, label, path);
        }

        private Candidate(Path path, boolean zip, String label, Path openPath) {
            this.path = path;
            this.zip = zip;
            this.label = label;
            this.openPath = openPath;
        }

        /** Whether this needs a private copy before it can be opened. */
        boolean needsCopy() {
            return zip && !"server".equals(label);
        }

        /** This candidate, opened from {@code copy}. */
        Candidate openingAt(Path copy) {
            return new Candidate(path, zip, label, copy);
        }

        String fingerprint() {
            try {
                return label + ":" + path + ":" + Files.size(path) + ":"
                        + Files.getLastModifiedTime(path).toMillis();
            } catch (IOException | RuntimeException gone) {
                return label + ":" + path + ":gone";
            }
        }
    }

    PackSources(HeimdallLogger logger, Path serverRoot, Path pluginsDir, Path cacheDir) {
        this.logger = logger;
        this.serverRoot = serverRoot;
        this.pluginsDir = pluginsDir;
        this.cacheDir = cacheDir;
    }

    /**
     * Every pack currently present, highest priority first. {@code operatorFolder} may be
     * {@code null}; {@code serverPack} is the downloaded server pack or {@code null}.
     */
    List<Candidate> discover(Path operatorFolder, Path serverPack) {
        List<Candidate> out = new ArrayList<Candidate>();
        if (operatorFolder != null) {
            out.addAll(packsIn(operatorFolder, "folder"));
        }
        if (serverPack != null && Files.isRegularFile(serverPack)) {
            out.add(new Candidate(serverPack, true, "server"));
        }
        if (pluginsDir != null) {
            Path itemsAdder = pluginsDir.resolve("ItemsAdder");
            Path generated = itemsAdder.resolve("output").resolve("generated.zip");
            Path uncompressed = itemsAdder.resolve("output_uncompressed");
            if (Files.isRegularFile(generated) && opens(generated)) {
                out.add(new Candidate(generated, true, "itemsadder"));
            } else if (Files.isDirectory(uncompressed)) {
                out.add(new Candidate(uncompressed, false, "itemsadder"));
            }
            out.addAll(packsIn(pluginsDir.resolve("Nexo").resolve("pack"), "nexo"));
            out.addAll(packsIn(pluginsDir.resolve("Oraxen").resolve("pack"), "oraxen"));
        }
        return out;
    }

    /** The combined fingerprint of {@code candidates} plus the vanilla directory. */
    static String fingerprint(List<Candidate> candidates, Path vanilla) {
        StringBuilder out = new StringBuilder();
        for (Candidate candidate : candidates) {
            out.append(candidate.fingerprint()).append('|');
        }
        return out.append("vanilla:").append(vanilla).toString();
    }

    /** Zips and pack-shaped directories directly in {@code folder}, sorted by name. */
    private static List<Candidate> packsIn(Path folder, String label) {
        if (!Files.isDirectory(folder)) {
            return Collections.emptyList();
        }
        List<Candidate> out = new ArrayList<Candidate>();
        // A folder that is itself an unpacked pack (Nexo and Oraxen can leave one).
        if (Files.isDirectory(folder.resolve("assets"))) {
            out.add(new Candidate(folder, false, label));
        }
        List<Path> children = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path child : stream) {
                children.add(child);
            }
        } catch (IOException | RuntimeException unreadable) {
            return out;
        }
        Collections.sort(children);
        for (Path child : children) {
            String name = child.getFileName().toString().toLowerCase(Locale.ROOT);
            if (Files.isRegularFile(child) && name.endsWith(".zip")) {
                out.add(new Candidate(child, true, label));
            } else if (Files.isDirectory(child) && !"assets".equals(name)
                    && (Files.isRegularFile(child.resolve("pack.mcmeta"))
                            || Files.isDirectory(child.resolve("assets")))) {
                out.add(new Candidate(child, false, label));
            }
        }
        return out;
    }

    private static boolean opens(Path zip) {
        try (java.util.zip.ZipFile ignored = new java.util.zip.ZipFile(zip.toFile())) {
            return true;
        } catch (IOException | RuntimeException protectedOrCorrupt) {
            return false;
        }
    }

    /**
     * Opens every candidate (each with its applicable overlays) followed by {@code vanilla}. A
     * candidate that will not open is skipped with a debug line naming the file, never its content.
     */
    PackStack open(List<Candidate> candidates, Path vanilla, int packFormat) {
        List<AssetRoot> roots = new ArrayList<AssetRoot>();
        java.util.Set<Path> copies = new java.util.HashSet<Path>();
        for (Candidate candidate : candidates) {
            try {
                AssetRoot root;
                if (candidate.zip) {
                    // Never the operator's or a plugin's file: the private copy, or the server
                    // pack, which is the cache's own. Copying happens on the asset thread.
                    if (candidate.needsCopy() && candidate.openPath.equals(candidate.path)) {
                        continue;
                    }
                    if (candidate.needsCopy()) {
                        copies.add(candidate.openPath);
                    }
                    root = new AssetRoot.Zip(candidate.openPath);
                } else {
                    root = new AssetRoot.Directory(candidate.path);
                }
                roots.add(AssetRoot.WithOverlays.of(root, packFormat));
            } catch (IOException | RuntimeException unreadable) {
                final String name = String.valueOf(candidate.path.getFileName());
                logger.debug(() -> "item images: skipped the pack " + name + " ("
                        + unreadable.getClass().getSimpleName() + ")");
            }
        }
        if (vanilla != null) {
            roots.add(new AssetRoot.Directory(vanilla));
        }
        return new PackStack(roots, fingerprint(candidates, vanilla) + "|format:" + packFormat,
                copies);
    }

    /** Where the private copy of {@code zip}, as it is now, lives (whether or not it exists). */
    private Path copyPath(Path zip) throws IOException {
        long size = Files.size(zip);
        long modified = Files.getLastModifiedTime(zip).toMillis();
        String key = VanillaAssets.sha1(zip.toAbsolutePath().toString()
                .getBytes(StandardCharsets.UTF_8)).substring(0, 12);
        return cacheDir.resolve("packs").resolve("open")
                .resolve(key + "-" + size + "-" + modified + ".zip");
    }

    /**
     * The private copy of {@code zip} if it is ready, else {@code null}. Two {@code stat} calls and
     * a hash of the path: cheap enough for the render thread, which never copies.
     */
    Path readyCopy(Path zip) {
        try {
            Path copy = copyPath(zip);
            return Files.isRegularFile(copy) && Files.size(copy) == Files.size(zip) ? copy : null;
        } catch (IOException | RuntimeException gone) {
            return null;
        }
    }

    /**
     * Makes the cache's copy of a pack zip, once per (path, size, modification time). Blocking: the
     * asset thread only.
     */
    Path privateCopy(Path zip) throws IOException {
        long size = Files.size(zip);
        if (size > MAX_SERVER_PACK_BYTES) {
            throw new AssetException("a pack zip is larger than " + MAX_SERVER_PACK_BYTES + " bytes");
        }
        Path copy = copyPath(zip);
        Path dir = copy.getParent();
        if (Files.isRegularFile(copy) && Files.size(copy) == size) {
            return copy;
        }
        Files.createDirectories(dir);
        Path partial = dir.resolve(".copy-" + instance + "-" + Long.toHexString(System.nanoTime()));
        try {
            Files.copy(zip, partial, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.move(partial, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return copy;
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /** Deletes download partials a crashed run left, once they are too old to be anyone's. */
    private static void sweepOldPartials(Path packs) {
        long cutoff = System.currentTimeMillis() - VanillaAssets.LEFTOVER_AGE_MS;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(packs, ".download-*")) {
            for (Path partial : stream) {
                if (Files.getLastModifiedTime(partial).toMillis() < cutoff) {
                    Files.deleteIfExists(partial);
                }
            }
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    /**
     * Deletes pack copies no stack uses any more, and copy leftovers older than
     * {@link VanillaAssets#LEFTOVER_AGE_MS}. Called after the previous stack has been closed, since
     * an open zip cannot be deleted on Windows; a file that still cannot be is tried next time.
     */
    void pruneCopies(java.util.Set<Path> inUse) {
        Path dir = cacheDir.resolve("packs").resolve("open");
        if (!Files.isDirectory(dir)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - VanillaAssets.LEFTOVER_AGE_MS;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                boolean stale = name.startsWith(".copy-")
                        ? Files.getLastModifiedTime(child).toMillis() < cutoff
                        : !inUse.contains(child);
                if (stale) {
                    try {
                        Files.deleteIfExists(child);
                    } catch (IOException stillOpen) {
                        // Next rebuild.
                    }
                }
            }
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    // ── server.properties ────────────────────────────────────────────────────

    /**
     * Downloads the pack {@code server.properties} names, if any, into the cache, and returns it.
     * Re-downloads when the URL or the expected SHA-1 changes, and, for a pack with no SHA-1 to pin
     * it, once the copy is a day old (the URL may serve new contents). A SHA-1 mismatch is an error
     * and leaves nothing behind. Blocking: the asset thread only.
     *
     * <p>Every fetch lands under a new name, {@code server-<url key>-<content hash>.zip}, and never
     * replaces a file in place: the stack opens the server pack where it lies, and on Windows a file
     * held open cannot be replaced or deleted. The superseded file is removed by
     * {@link #pruneServerPacks} once the stack has switched to the new one.
     */
    Path serverPack(HttpSource http) throws IOException {
        Properties properties = new Properties();
        Path file = serverRoot == null ? null : serverRoot.resolve("server.properties");
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        String url = properties.getProperty("resource-pack", "").trim();
        if (url.isEmpty()) {
            return null;
        }
        String sha1 = properties.getProperty("resource-pack-sha1", "").trim().toLowerCase(Locale.ROOT);
        String key = VanillaAssets.sha1((url + "|" + sha1).getBytes(StandardCharsets.UTF_8));
        Path packs = cacheDir.resolve("packs");
        String prefix = "server-" + key.substring(0, 16) + "-";
        Path current = newest(packs, prefix);
        if (current != null) {
            boolean stale = sha1.isEmpty() && System.currentTimeMillis()
                    - Files.getLastModifiedTime(current).toMillis() > UNHASHED_PACK_MAX_AGE_MS;
            if (!stale) {
                return current;
            }
        }
        Files.createDirectories(packs);
        sweepOldPartials(packs);
        Path partial = packs.resolve(".download-" + instance + "-"
                + Long.toHexString(System.nanoTime()));
        try {
            http.download(url, partial, MAX_SERVER_PACK_BYTES);
            if (!sha1.isEmpty() && !sha1.equals(VanillaAssets.sha1(partial))) {
                throw new AssetException("the server resource pack does not match resource-pack-sha1");
            }
            String content = VanillaAssets.sha1(partial).substring(0, 12);
            Path target = packs.resolve(prefix + content + ".zip");
            if (Files.isRegularFile(target)) {
                // Same bytes as a file we already have: keep that one, and mark it fresh. The
                // touch may fail on a file held open; then the next prepare simply fetches again.
                try {
                    Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(
                            System.currentTimeMillis()));
                } catch (IOException heldOpen) {
                    // See above.
                }
                return target;
            }
            Files.move(partial, target);
            logger.info("item images: using the server resource pack ("
                    + Files.size(target) + " bytes)");
            return target;
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /** The most recently written {@code <prefix>*.zip} in {@code packs}, or {@code null}. */
    private static Path newest(Path packs, String prefix) throws IOException {
        if (!Files.isDirectory(packs)) {
            return null;
        }
        Path best = null;
        long bestTime = Long.MIN_VALUE;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(packs, prefix + "*.zip")) {
            for (Path candidate : stream) {
                long time = Files.getLastModifiedTime(candidate).toMillis();
                if (best == null || time > bestTime) {
                    best = candidate;
                    bestTime = time;
                }
            }
        }
        return best;
    }

    /**
     * Deletes every downloaded server pack except {@code keep}, once the stack no longer has the
     * others open. A file younger than {@link VanillaAssets#LEFTOVER_AGE_MS} is left alone: it may
     * be a fetch the asset thread finished a moment ago and has not published yet. A file that
     * cannot be deleted (still open on Windows) is tried at the next rebuild.
     */
    void pruneServerPacks(Path keep) {
        Path packs = cacheDir.resolve("packs");
        if (!Files.isDirectory(packs)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - VanillaAssets.LEFTOVER_AGE_MS;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(packs, "server-*.zip")) {
            for (Path old : stream) {
                if (old.equals(keep) || Files.getLastModifiedTime(old).toMillis() >= cutoff) {
                    continue;
                }
                try {
                    Files.deleteIfExists(old);
                } catch (IOException stillOpen) {
                    // Next rebuild.
                }
            }
        } catch (IOException ignored) {
            // Best effort.
        }
    }
}
