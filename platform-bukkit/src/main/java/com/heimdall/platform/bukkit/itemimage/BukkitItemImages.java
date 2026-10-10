package com.heimdall.platform.bukkit.itemimage;

import com.heimdall.core.config.YamlProbe;
import com.heimdall.core.items.ChatItem;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.ItemImages;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Tooltip-card images for items shown in chat, on a Bukkit backend. Departure D86.
 *
 * <h2>Two threads of its own, neither the server's nor the chat thread</h2>
 *
 * <ul>
 *   <li>{@code heimdall-item-assets} prepares: it fetches and verifies the vanilla client assets for
 *       this server's version ({@link VanillaAssets}) and the pack {@code server.properties} sends.
 *       Network work, minutes at worst, once.
 *   <li>{@code heimdall-item-render} draws: it keeps the pack stack current ({@link PackSources},
 *       rescanned at most every {@value #RESCAN_SECONDS} seconds and rebuilt only when a file
 *       changed), resolves the model, draws the card and encodes the PNG. One render at a time,
 *       at most {@value #RENDER_QUEUE} waiting; past that a render is refused rather than queued,
 *       which the bridge sees as "no image" and relays the text.
 * </ul>
 *
 * <p>Each render has a {@value #RENDER_BUDGET_MS} ms budget checked between steps; an overrun or any
 * failure yields no image. The bridge waits far less than that for its line, so a slow first render
 * costs that one image and warms the caches for the next. Rendered PNGs are kept in a small LRU keyed
 * by the pack stack's fingerprint and the item's normalised model, so the same item shown twice is
 * drawn once. The cache key is a SHA-256 of the item's normalised model, never its text, and an
 * entry lives at most {@value #CACHE_TTL_MS} ms: a picture of an item, not a record of it, and not
 * kept past the conversation it was shown in. A card that encodes larger than
 * {@value #PREFERRED_MAX_PNG_BYTES} bytes is redrawn at
 * half scale.
 *
 * <h2>Configuration</h2>
 *
 * <p>The dashboard's {@code itemImages} bridge setting decides whether anything is drawn at all, and
 * nothing here runs until it asks: a server that turned it off never downloads. Two local knobs live
 * in an optional {@code plugins/Heimdall/item-images.yml}, because they are about this machine's disk
 * and network rather than about the product: {@code download-vanilla-assets} (default {@code true};
 * off means vanilla icons are placeholders and text uses the built-in fallback font, unless a pack
 * supplies them) and {@code pack-folder} (default {@code item-images/packs}, relative to the plugin's
 * folder).
 *
 * <h2>Without {@code java.awt}</h2>
 *
 * <p>A JVM without a usable {@code java.desktop} (a jlinked minimal runtime) makes
 * {@link #available()} answer {@code false} and nothing else changes: the bridge relays text. This
 * class itself names no AWT type, so it loads either way; only the renderer classes do, and they are
 * touched only after the probe passes.
 *
 * <p>Logs name files, versions and counts, never an item's name or lore.
 */
public final class BukkitItemImages implements ItemImages, AutoCloseable {

    static final long RENDER_BUDGET_MS = 3000L;
    static final int RENDER_QUEUE = 16;
    static final long RESCAN_SECONDS = 10L;
    static final int CACHE_ENTRIES = 128;
    static final long CACHE_BYTES = 16L * 1024 * 1024;
    static final int PREFERRED_MAX_PNG_BYTES = 256 * 1024;
    static final int SCALE = 2;
    static final long PREPARE_RETRY_MS = 10L * 60 * 1000;

    /**
     * After a successful prepare, the next one is six hours out: cheap unless a pack URL went
     * stale, and often enough that this server's version cache stays marked in use (see
     * {@link VanillaAssets#IN_USE_MS}) for any sibling server pruning the shared folder.
     */
    static final long PREPARE_REFRESH_MS = 6L * 60 * 60 * 1000;

    /** How long a rendered card is kept. */
    static final long CACHE_TTL_MS = 10L * 60 * 1000;

    /** The optional local file, in the plugin's folder. */
    static final String CONFIG_FILE = "item-images.yml";

    private final HeimdallLogger logger;
    private final Path dataDir;
    private final HttpSource http;
    private final ItemDefaults defaults;
    private final String version;
    private final VanillaAssets vanilla;
    private final PackSources sources;

    private final ThreadPoolExecutor assets;
    private final ThreadPoolExecutor renders;

    /** Wall clock for the render cache's lifetime; a field so a test can move time. */
    volatile java.util.function.LongSupplier clock = new java.util.function.LongSupplier() {
        @Override
        public long getAsLong() {
            return System.currentTimeMillis();
        }
    };

    /** How often the expiry timer purges dead cards; a field so a test can shorten it. */
    volatile long expiryPeriodMs = 60_000L;

    /**
     * Posts a purge of expired cards to the render thread every {@link #expiryPeriodMs}, so a card
     * dies on time on an idle server too, not only when the next render happens to look. Started
     * by the first card cached; render-thread state is only ever touched on the render thread.
     */
    private volatile java.util.concurrent.ScheduledThreadPoolExecutor expiry;

    /** Pack zips being copied on the asset thread right now. */
    private final java.util.Set<Path> copying =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** When a copy last failed, per pack, so a broken file is retried a minute later, not per scan. */
    private final java.util.Map<Path, Long> copyFailedAt =
            new java.util.concurrent.ConcurrentHashMap<Path, Long>();

    /** How long a pack whose copy failed waits before the next attempt. */
    static final long COPY_RETRY_MS = 60_000L;

    /** Copies attempted; for the retry gate's test. */
    final java.util.concurrent.atomic.AtomicInteger copyAttempts =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Rescan interval; a field so a test can rescan on every render. */
    volatile long rescanNanos = TimeUnit.SECONDS.toNanos(RESCAN_SECONDS);

    /** {@code null} until probed; then whether AWT works here. */
    private volatile Boolean awt;
    private volatile boolean closed;

    private final AtomicBoolean preparing = new AtomicBoolean();
    /** Cards over this many bytes are redrawn at scale 1. A field so a test can lower it. */
    volatile int preferredMaxPngBytes = PREFERRED_MAX_PNG_BYTES;
    private final AtomicLong nextPrepareAt = new AtomicLong(Long.MIN_VALUE);
    private final AtomicInteger assetGeneration = new AtomicInteger();

    private volatile Path vanillaDir;
    private volatile Path serverPack;
    private volatile Path packFolder;
    private volatile Map<String, String> english = Collections.emptyMap();

    // ── Render thread only ───────────────────────────────────────────────────

    private PackStack stack;
    private CardRenderer renderer;
    private int stackGeneration = -1;
    private long nextScanAt;
    private long cacheBytes;
    private final LinkedHashMap<String, Cached> cache =
            new LinkedHashMap<String, Cached>(32, 0.75f, true);

    /** A rendered card and when it was drawn. */
    private static final class Cached {
        final byte[] png;
        final long drawnAt;

        Cached(byte[] png, long drawnAt) {
            this.png = png;
            this.drawnAt = drawnAt;
        }
    }

    BukkitItemImages(HeimdallLogger logger, Path dataDir, Path pluginsDir, Path serverRoot,
            HttpSource http, ItemDefaults defaults, String version) {
        this.logger = logger;
        this.dataDir = dataDir;
        this.http = http;
        this.defaults = defaults;
        this.version = version;
        Path cache = dataDir.resolve("cache");
        this.vanilla = new VanillaAssets(logger, http, cache.resolve("assets"));
        this.sources = new PackSources(logger, serverRoot, pluginsDir, cache);
        this.packFolder = dataDir.resolve("item-images").resolve("packs");
        this.assets = executor("heimdall-item-assets", 4);
        this.renders = executor("heimdall-item-render", RENDER_QUEUE);
    }

    /** The production wiring: this plugin's folder, the real network, the running server. */
    public static BukkitItemImages create(HeimdallLogger logger, Plugin plugin) {
        Path dataDir = plugin.getDataFolder().toPath().toAbsolutePath();
        Path pluginsDir = dataDir.getParent();
        Path serverRoot = pluginsDir == null ? null : pluginsDir.getParent();
        String version;
        try {
            version = versionOf(Bukkit.getBukkitVersion());
        } catch (Throwable unavailable) {
            version = "unknown";
        }
        return new BukkitItemImages(logger, dataDir, pluginsDir, serverRoot, new HttpSource.Url(),
                new BukkitItemDefaults(), version);
    }

    /** {@code 1.21.5-R0.1-SNAPSHOT} to {@code 1.21.5}; {@code 26.1-R0.1-SNAPSHOT} to {@code 26.1}. */
    static String versionOf(String bukkitVersion) {
        if (bukkitVersion == null) {
            return "unknown";
        }
        int dash = bukkitVersion.indexOf('-');
        return (dash < 0 ? bukkitVersion : bukkitVersion.substring(0, dash)).trim();
    }

    private static ThreadPoolExecutor executor(final String name, int queue) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(queue), new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable, name);
                        thread.setDaemon(true);
                        thread.setPriority(Thread.NORM_PRIORITY - 1);
                        return thread;
                    }
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    // ── ItemImages ───────────────────────────────────────────────────────────

    @Override
    public boolean available() {
        if (closed) {
            return false;
        }
        Boolean probed = awt;
        if (probed == null) {
            probed = probeAwt();
            awt = probed;
            if (!probed) {
                logger.warn("item images are unavailable: this Java runtime has no usable "
                        + "java.awt. Chat items relay as [Name] text only.");
            }
        }
        return probed;
    }

    /** Whether an ARGB image can be made and a PNG writer exists, by name, without linking AWT here. */
    private static boolean probeAwt() {
        try {
            Class.forName("java.awt.image.BufferedImage");
            return AwtProbe.works();
        } catch (Throwable unusable) {
            return false;
        }
    }

    @Override
    public void prepare() {
        if (!available() || closed) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextPrepareAt.get() || !preparing.compareAndSet(false, true)) {
            return;
        }
        try {
            assets.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        prepareNow();
                    } finally {
                        preparing.set(false);
                    }
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            preparing.set(false);
        }
    }

    @Override
    public String translate(String key) {
        return key == null ? null : english.get(key);
    }

    @Override
    public CompletableFuture<byte[]> render(final ChatItem item) {
        final CompletableFuture<byte[]> result = new CompletableFuture<byte[]>();
        if (item == null || !available()) {
            result.complete(null);
            return result;
        }
        prepare();
        try {
            renders.execute(new Runnable() {
                @Override
                public void run() {
                    result.complete(renderNow(item));
                }
            });
        } catch (RejectedExecutionException full) {
            result.complete(null);
        }
        return result;
    }

    @Override
    public void close() {
        closed = true;
        java.util.concurrent.ScheduledThreadPoolExecutor timer = expiry;
        if (timer != null) {
            timer.shutdownNow();
        }
        assets.shutdownNow();
        try {
            renders.execute(new Runnable() {
                @Override
                public void run() {
                    closeStack();
                }
            });
        } catch (RejectedExecutionException alreadyGone) {
            // Nothing queued it; the stack is closed below once the thread has stopped.
        }
        renders.shutdown();
        try {
            if (!renders.awaitTermination(2, TimeUnit.SECONDS)) {
                renders.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            renders.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Asset thread ─────────────────────────────────────────────────────────

    private void prepareNow() {
        Path config = dataDir.resolve(CONFIG_FILE);
        boolean download = YamlProbe.flag(config, "download-vanilla-assets", true, logger);
        Object folder = YamlProbe.value(config, "pack-folder", logger);
        if (folder instanceof String && !((String) folder).trim().isEmpty()) {
            packFolder = dataDir.resolve(((String) folder).trim()).normalize();
        }

        boolean failed = false;
        vanilla.sweepLeftovers();
        Path dir = vanilla.cached(version);
        if (dir == null && download) {
            try {
                dir = vanilla.ensure(version);
            } catch (IOException | RuntimeException error) {
                failed = true;
                // AssetException messages are ours (host, version, size); anything else is named
                // by class only, since a transport message can carry a full URL.
                logger.warn("item images: could not fetch the vanilla " + version
                        + " assets (" + AssetException.describe(error) + "); vanilla icons will "
                        + "be placeholders. Retrying in 10 minutes.");
            }
        }
        if (dir != null) {
            VanillaAssets.touch(dir);
            final int pruned = vanilla.pruneOthers(dir);
            if (pruned > 0) {
                logger.debug(() -> "item images: removed " + pruned + " old asset version(s)");
            }
        }
        try {
            Path pack = sources.serverPack(http);
            serverPack = pack;
        } catch (IOException | RuntimeException error) {
            // The previous copy, if any, stays in use until a fetch succeeds.
            failed = true;
            logger.warn("item images: could not fetch the server resource pack ("
                    + AssetException.describe(error) + "). Retrying in 10 minutes.");
        }
        vanillaDir = dir;
        nextPrepareAt.set(System.currentTimeMillis()
                + (failed ? PREPARE_RETRY_MS : PREPARE_REFRESH_MS));
        assetGeneration.incrementAndGet();
        // Warm the stack (and with it the English names the chat rewrite uses) now, rather than on
        // the first item someone shows.
        try {
            renders.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        refreshStack(true);
                    } catch (Throwable failedRefresh) {
                        logger.debug(() -> "item images: warming the pack stack failed: "
                                + failedRefresh.getClass().getName());
                    }
                }
            });
        } catch (RejectedExecutionException busy) {
            // The next render refreshes it instead.
        }
    }

    // ── Render thread ────────────────────────────────────────────────────────

    private byte[] renderNow(ChatItem item) {
        if (closed) {
            return null;
        }
        long started = System.nanoTime();
        try {
            Deadline deadline = Deadline.in(RENDER_BUDGET_MS);
            refreshStack(false);
            String key = cacheKey(stack, item);
            byte[] cached = cached(key);
            if (cached != null) {
                return cached;
            }
            byte[] png = renderer.render(item, this, defaults, SCALE, deadline);
            if (png.length > preferredMaxPngBytes) {
                // Far larger than a card should be (a huge lore block, a noisy HD texture): half
                // scale rather than ship something near the wire cap.
                png = renderer.render(item, this, defaults, 1, deadline);
            }
            remember(key, png);
            final int size = png.length;
            final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            logger.debug(() -> "item images: rendered a card (" + size + " bytes, " + ms + " ms)");
            return png;
        } catch (Deadline.Exceeded slow) {
            logger.debug(() -> "item images: a render exceeded its " + RENDER_BUDGET_MS
                    + " ms budget; the line relays without it");
            return null;
        } catch (Throwable failed) {
            // Class name only: an exception message from a pack parser could quote a file, and
            // nothing here may risk quoting an item's text.
            logger.debug(() -> "item images: a render failed (" + failed.getClass().getName()
                    + ")");
            return null;
        }
    }

    /** A live cached card, or {@code null}; an expired one is dropped on the way. */
    private byte[] cached(String key) {
        Cached entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (clock.getAsLong() - entry.drawnAt > CACHE_TTL_MS) {
            cache.remove(key);
            cacheBytes -= entry.png.length;
            return null;
        }
        return entry.png;
    }

    /**
     * The render cache's key: the stack's fingerprint and a SHA-256 of the item's normalised model.
     * The model's text (names, lore) never appears in it.
     */
    static String cacheKey(PackStack stack, ChatItem item) {
        return stack.fingerprint() + "\n" + sha256(item.cacheKey());
    }

    /** Drops every expired card. Render thread only. */
    private void expireNow() {
        long now = clock.getAsLong();
        java.util.Iterator<Map.Entry<String, Cached>> entries = cache.entrySet().iterator();
        while (entries.hasNext()) {
            Cached entry = entries.next().getValue();
            if (now - entry.drawnAt > CACHE_TTL_MS) {
                cacheBytes -= entry.png.length;
                entries.remove();
            }
        }
    }

    private void startExpiryTimer() {
        if (expiry != null || closed) {
            return;
        }
        java.util.concurrent.ScheduledThreadPoolExecutor timer =
                new java.util.concurrent.ScheduledThreadPoolExecutor(1, new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable, "heimdall-item-expiry");
                        thread.setDaemon(true);
                        return thread;
                    }
                });
        final Runnable purge = new Runnable() {
            @Override
            public void run() {
                expireNow();
            }
        };
        long period = Math.max(1L, expiryPeriodMs);
        timer.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    renders.execute(purge);
                } catch (RejectedExecutionException busyOrClosed) {
                    // Full queue: the next tick tries again. Closed: the cards go with the cache.
                }
            }
        }, period, period, TimeUnit.MILLISECONDS);
        expiry = timer;
        if (closed) {
            timer.shutdownNow();
        }
    }

    /** How many cards are cached, read on the render thread; for tests. */
    int cachedCards() throws Exception {
        return renders.submit(new java.util.concurrent.Callable<Integer>() {
            @Override
            public Integer call() {
                return cache.size();
            }
        }).get(10, TimeUnit.SECONDS);
    }

    private void remember(String key, byte[] png) {
        startExpiryTimer();
        long now = clock.getAsLong();
        Cached previous = cache.put(key, new Cached(png, now));
        if (previous != null) {
            cacheBytes -= previous.png.length;
        }
        cacheBytes += png.length;
        java.util.Iterator<Map.Entry<String, Cached>> eldest = cache.entrySet().iterator();
        while (eldest.hasNext()) {
            Cached entry = eldest.next().getValue();
            boolean expired = now - entry.drawnAt > CACHE_TTL_MS;
            boolean over = cache.size() > CACHE_ENTRIES || cacheBytes > CACHE_BYTES;
            if (!expired && !over) {
                continue;
            }
            if (entry.png == png) {
                continue;
            }
            cacheBytes -= entry.png.length;
            eldest.remove();
        }
    }

    /** The cache key for an item: a SHA-256 of its normalised model, so no text is held as a key. */
    static String sha256(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException(impossible);
        }
    }

    /** Rebuilds the pack stack if assets arrived or any pack changed; cheap when nothing did. */
    private void refreshStack(boolean force) {
        long now = System.nanoTime();
        int generation = assetGeneration.get();
        if (!force && stack != null && generation == stackGeneration && now - nextScanAt < 0) {
            return;
        }
        nextScanAt = now + rescanNanos;
        Path vanillaNow = vanillaDir;
        List<PackSources.Candidate> candidates = new java.util.ArrayList<PackSources.Candidate>();
        for (PackSources.Candidate candidate : sources.discover(packFolder, serverPack)) {
            if (!candidate.needsCopy()) {
                candidates.add(candidate);
                continue;
            }
            Path copy = sources.readyCopy(candidate.path);
            if (copy != null) {
                candidates.add(candidate.openingAt(copy));
            } else {
                // Left out until its copy is ready, and out of the fingerprint with it, so the
                // rescan after the copy (or after a failed copy's retry) rebuilds the stack.
                requestCopy(candidate.path);
            }
        }
        int format = VanillaAssets.packFormat(vanillaNow);
        String fingerprint = PackSources.fingerprint(candidates, vanillaNow) + "|format:" + format;
        if (stack != null && fingerprint.equals(stack.fingerprint())) {
            stackGeneration = generation;
            return;
        }
        PackStack fresh = sources.open(candidates, vanillaNow, format);
        closeStack();
        // After the old stack's zips are closed: Windows cannot delete an open file.
        sources.pruneCopies(fresh.copies());
        sources.pruneServerPacks(serverPack);
        stack = fresh;
        renderer = new CardRenderer(fresh);
        stackGeneration = generation;
        cache.clear();
        cacheBytes = 0;
        english = Collections.unmodifiableMap(fresh.english(Collections.singletonList("minecraft")));
        final int roots = fresh.size();
        final int names = english.size();
        logger.debug(() -> "item images: pack stack rebuilt (" + roots + " source(s), " + names
                + " English names)");
    }

    /** Copies a pack zip on the asset thread, then has the render thread rebuild the stack. */
    private void requestCopy(final Path zip) {
        Long failed = copyFailedAt.get(zip);
        if (closed || (failed != null && clock.getAsLong() - failed < COPY_RETRY_MS)
                || !copying.add(zip)) {
            return;
        }
        try {
            assets.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        copyAttempts.incrementAndGet();
                        sources.privateCopy(zip);
                        copyFailedAt.remove(zip);
                    } catch (Throwable error) {
                        copyFailedAt.put(zip, clock.getAsLong());
                        logger.debug(() -> "item images: copying a pack failed ("
                                + AssetException.describe(error) + "); retrying in a minute");
                    } finally {
                        copying.remove(zip);
                    }
                    assetGeneration.incrementAndGet();
                    try {
                        renders.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    refreshStack(false);
                                } catch (Throwable ignored) {
                                    // The next render refreshes it instead.
                                }
                            }
                        });
                    } catch (RejectedExecutionException busy) {
                        // The next render refreshes it instead.
                    }
                }
            });
        } catch (RejectedExecutionException full) {
            copying.remove(zip);
        }
    }

    private void closeStack() {
        PackStack old = stack;
        stack = null;
        renderer = null;
        if (old != null) {
            old.close();
        }
    }

    /**
     * The AWT probe, in its own class so that {@link BukkitItemImages} never links an AWT type: if
     * {@code java.desktop} is missing, only this class fails to load, inside the probe's catch.
     */
    private static final class AwtProbe {

        /**
         * Exercises every AWT path a render uses: an ARGB image, a {@code Graphics2D} (the icon's
         * affine draws), and a real PNG encode through the same in-memory stream a card uses. A
         * runtime that has the classes but cannot rasterise or encode fails here, once, rather
         * than on every render.
         */
        static boolean works() throws java.io.IOException {
            java.awt.image.BufferedImage image =
                    new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, 0xFF000000);
            image.createGraphics().dispose();
            return CardRenderer.encode(image).length > 0;
        }
    }
}
