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
 * <p>Discovery is a handful of {@code stat} calls, cheap enough to repeat every few seconds; the
 * {@link #fingerprint} of what it found (paths, sizes, modification times) is what decides whether
 * the stack has to be rebuilt, so a pack that ItemsAdder regenerates is picked up without a restart
 * and without re-reading anything per message.
 */
final class PackSources {

    /** Largest server resource pack downloaded. */
    static final long MAX_SERVER_PACK_BYTES = 250L * 1024 * 1024;

    private final HeimdallLogger logger;
    private final Path serverRoot;
    private final Path pluginsDir;
    private final Path cacheDir;

    /** A pack on disk and whether it is a zip or a directory. */
    static final class Candidate {

        final Path path;
        final boolean zip;
        final String label;

        Candidate(Path path, boolean zip, String label) {
            this.path = path;
            this.zip = zip;
            this.label = label;
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
        for (Candidate candidate : candidates) {
            try {
                AssetRoot root = candidate.zip
                        ? new AssetRoot.Zip(candidate.path)
                        : new AssetRoot.Directory(candidate.path);
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
        return new PackStack(roots, fingerprint(candidates, vanilla) + "|format:" + packFormat);
    }

    // ── server.properties ────────────────────────────────────────────────────

    /**
     * Downloads the pack {@code server.properties} names, if any, into the cache, and returns it.
     * Re-downloads only when the URL or the expected SHA-1 changes; a SHA-1 mismatch is an error and
     * leaves nothing behind. Blocking: the asset thread only.
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
        Path target = packs.resolve("server-" + key.substring(0, 16) + ".zip");
        if (Files.isRegularFile(target)) {
            return target;
        }
        Files.createDirectories(packs);
        Path partial = packs.resolve(".download-" + Long.toHexString(System.nanoTime()));
        try {
            http.download(url, partial, MAX_SERVER_PACK_BYTES);
            if (!sha1.isEmpty() && !sha1.equals(VanillaAssets.sha1(partial))) {
                throw new IOException("the server resource pack does not match resource-pack-sha1");
            }
            Files.move(partial, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            removeSuperseded(packs, target);
            logger.info("item images: using the server resource pack ("
                    + Files.size(target) + " bytes)");
            return target;
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /** Deletes earlier server packs once a new URL or hash has replaced them. Best effort. */
    private static void removeSuperseded(Path packs, Path current) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(packs, "server-*.zip")) {
            for (Path old : stream) {
                if (!old.equals(current)) {
                    Files.deleteIfExists(old);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A file still open elsewhere is removed on a later download.
        }
    }
}
