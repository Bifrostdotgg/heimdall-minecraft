package com.heimdall.platform.bukkit.itemimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.items.ChatItem;
import com.heimdall.core.items.HoverTags;
import com.heimdall.core.items.ItemTranslations;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.ItemCaptures;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The whole Bukkit-side service, headless, with a fake network and fixture packs. */
class BukkitItemImagesTest {

    @TempDir
    Path server;

    private final RecordingLogger logger = new RecordingLogger(true);
    private BukkitItemImages images;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @AfterEach
    void close() {
        if (images != null) {
            images.close();
        }
    }

    private BukkitItemImages build(HttpSource http) {
        Path plugins = server.resolve("plugins");
        images = new BukkitItemImages(logger, plugins.resolve("Heimdall"), plugins, server, http,
                ItemDefaults.NONE, "1.21.5");
        return images;
    }

    private static ChatItem wardedJar() {
        return HoverTags.scan(ItemCaptures.wardedJar(), ItemTranslations.NONE).get(0).item();
    }

    @Test
    void offlineAndWithoutAssetsItStillDrawsACard() throws Exception {
        BukkitItemImages service = build(new Fixtures.FakeHttp());
        assertTrue(service.available(), "a headless JDK has a usable java.awt");

        byte[] png = service.render(wardedJar()).get(10, TimeUnit.SECONDS);

        assertNotNull(png, "placeholder icon, built-in font, classic box");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertTrue(image.getWidth() > 100);
        // Preparing runs on its own thread, alongside the render; wait for its verdict.
        long deadline = System.currentTimeMillis() + 10_000;
        while (!logger.logged(LogLevel.WARN, "could not fetch the vanilla 1.21.5 assets")
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(logger.logged(LogLevel.WARN, "could not fetch the vanilla 1.21.5 assets"));
    }

    @Test
    void theSameItemIsDrawnOnce() throws Exception {
        BukkitItemImages service = build(new Fixtures.FakeHttp());
        byte[] first = service.render(wardedJar()).get(10, TimeUnit.SECONDS);
        byte[] second = service.render(wardedJar()).get(10, TimeUnit.SECONDS);
        assertSame(first, second, "served from the render cache");
    }

    @Test
    void anItemsAdderPackIsPickedUpAndItsNamesAreTranslated() throws Exception {
        Path generated = server.resolve("plugins/ItemsAdder/output/generated.zip");
        Files.createDirectories(generated.getParent());
        Files.write(generated, Fixtures.zip(Fixtures.files(
                "assets/minecraft/lang/en_us.json", "{\"item.minecraft.heart_of_the_sea\":\"Heart\"}")));
        BukkitItemImages service = build(new Fixtures.FakeHttp());
        // Download switched off locally, so preparing touches no network at all.
        Files.createDirectories(server.resolve("plugins/Heimdall"));
        Files.write(server.resolve("plugins/Heimdall/item-images.yml"),
                Fixtures.utf8("download-vanilla-assets: false\n"));

        assertNotNull(service.render(ChatItem.builder("heart_of_the_sea").build())
                .get(10, TimeUnit.SECONDS));
        long deadline = System.currentTimeMillis() + 10_000;
        while (service.translate("item.minecraft.heart_of_the_sea") == null
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals("Heart", service.translate("item.minecraft.heart_of_the_sea"));
        assertEquals("[Heart]", HoverTags.rewrite("<hover:show_item:heart_of_the_sea:1>[x]",
                service).text(), "the chat rewrite uses the pack's English names");
        assertFalse(logger.logged(LogLevel.WARN, "could not fetch the vanilla"),
                "download-vanilla-assets: false means no fetch was attempted");
    }

    @Test
    void anOversizedCardIsRedrawnAtHalfScale() throws Exception {
        BukkitItemImages normal = build(new Fixtures.FakeHttp());
        byte[] full = normal.render(wardedJar()).get(10, TimeUnit.SECONDS);
        normal.close();

        BukkitItemImages strict = build(new Fixtures.FakeHttp());
        strict.preferredMaxPngBytes = 1;
        byte[] half = strict.render(wardedJar()).get(10, TimeUnit.SECONDS);

        int fullWidth = ImageIO.read(new ByteArrayInputStream(full)).getWidth();
        int halfWidth = ImageIO.read(new ByteArrayInputStream(half)).getWidth();
        assertEquals(fullWidth / 2, halfWidth);
    }

    @Test
    void theRenderCacheKeyIsAHashNotTheText() {
        String key = BukkitItemImages.sha256(wardedJar().cacheKey());
        assertEquals(64, key.length());
        assertFalse(key.contains("Warded"));
    }

    @Test
    void cardsExpireOnTheTimerEvenWhenNothingRenders() throws Exception {
        BukkitItemImages service = build(new Fixtures.FakeHttp());
        final java.util.concurrent.atomic.AtomicLong now =
                new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        service.clock = new java.util.function.LongSupplier() {
            @Override
            public long getAsLong() {
                return now.get();
            }
        };
        service.expiryPeriodMs = 20L;

        assertNotNull(service.render(wardedJar()).get(10, TimeUnit.SECONDS));
        assertEquals(1, service.cachedCards());
        Thread.sleep(100);
        assertEquals(1, service.cachedCards(), "the timer runs, and a live card survives it");

        now.addAndGet(BukkitItemImages.CACHE_TTL_MS + 1);
        long deadline = System.currentTimeMillis() + 5_000;
        while (service.cachedCards() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, service.cachedCards(), "purged by the timer, with no render to notice");
    }

    @Test
    void theRenderCacheKeyCarriesNoItemText() {
        String key = BukkitItemImages.cacheKey(Fixtures.stack(), wardedJar());
        assertFalse(key.contains("Warded"), key);
        assertFalse(key.contains("Throw it"), key);
        assertTrue(key.endsWith(BukkitItemImages.sha256(wardedJar().cacheKey())));
    }

    @Test
    void prepareMarksItsCacheAndPrunesOnlyStaleSiblings() throws Exception {
        Path assets = server.resolve("plugins/Heimdall/cache/assets");
        String sha = "0123456789012345678901234567890123456789";
        Fixtures.write(assets.resolve("1.21.5"), Fixtures.files(VanillaAssets.MARKER, sha));
        Fixtures.write(assets.resolve("1.21.4"), Fixtures.files(VanillaAssets.MARKER, sha));
        Fixtures.write(assets.resolve("1.21.3"), Fixtures.files(VanillaAssets.MARKER, sha));
        long old = System.currentTimeMillis() - 2 * VanillaAssets.IN_USE_MS;
        for (String version : new String[] {"1.21.5", "1.21.4"}) {
            Files.setLastModifiedTime(assets.resolve(version).resolve(VanillaAssets.MARKER),
                    java.nio.file.attribute.FileTime.fromMillis(old));
        }
        BukkitItemImages service = build(new Fixtures.FakeHttp());

        service.prepare();
        long deadline = System.currentTimeMillis() + 10_000;
        while (Files.exists(assets.resolve("1.21.4")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertFalse(Files.exists(assets.resolve("1.21.4")), "stale sibling pruned");
        assertTrue(Files.exists(assets.resolve("1.21.3")), "recently used sibling kept");
        assertTrue(Files.getLastModifiedTime(assets.resolve("1.21.5").resolve(VanillaAssets.MARKER))
                .toMillis() > old, "this server's own cache is marked in use");
    }

    @Test
    void aRegeneratedPackIsRecopiedAndTheOldCopyPruned() throws Exception {
        Path generated = server.resolve("plugins/ItemsAdder/output/generated.zip");
        Files.createDirectories(generated.getParent());
        Files.write(generated, Fixtures.zip(Fixtures.files(
                "assets/minecraft/lang/en_us.json", "{\"item.minecraft.stone\":\"One\"}")));
        BukkitItemImages service = build(new Fixtures.FakeHttp());
        service.rescanNanos = 0L;
        Files.createDirectories(server.resolve("plugins/Heimdall"));
        Files.write(server.resolve("plugins/Heimdall/item-images.yml"),
                Fixtures.utf8("download-vanilla-assets: false\n"));

        awaitName(service, "One");
        Files.write(generated, Fixtures.zip(Fixtures.files(
                "assets/minecraft/lang/en_us.json", "{\"item.minecraft.stone\":\"Two!\"}")));
        Files.setLastModifiedTime(generated, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() + 60_000L));
        awaitName(service, "Two!");

        Path open = server.resolve("plugins/Heimdall/cache/packs/open");
        long deadline = System.currentTimeMillis() + 10_000;
        while (Files.list(open).count() != 1 && System.currentTimeMillis() < deadline) {
            service.render(ChatItem.builder("stone").build()).get(10, TimeUnit.SECONDS);
            Thread.sleep(20);
        }
        assertEquals(1, Files.list(open).count(), "only the current copy is kept");
        assertTrue(Files.exists(generated), "the original was never held open");
    }

    private static void awaitName(BukkitItemImages service, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!expected.equals(service.translate("item.minecraft.stone"))
                && System.currentTimeMillis() < deadline) {
            service.render(ChatItem.builder("stone").build()).get(10, TimeUnit.SECONDS);
            Thread.sleep(20);
        }
        assertEquals(expected, service.translate("item.minecraft.stone"));
    }

    @Test
    void aFailedPackCopyWaitsAMinuteBeforeTheNextAttempt() throws Exception {
        Path generated = server.resolve("plugins/ItemsAdder/output/generated.zip");
        Files.createDirectories(generated.getParent());
        Files.write(generated, Fixtures.zip(Fixtures.files("pack.mcmeta", "{}")));
        // A file where the copies' directory should be: every copy fails.
        Path packs = server.resolve("plugins/Heimdall/cache/packs");
        Files.createDirectories(packs);
        Files.write(packs.resolve("open"), new byte[] {1});
        Files.write(server.resolve("plugins/Heimdall/item-images.yml"),
                Fixtures.utf8("download-vanilla-assets: false\n"));
        BukkitItemImages service = build(new Fixtures.FakeHttp());
        service.rescanNanos = 0L;
        final java.util.concurrent.atomic.AtomicLong now =
                new java.util.concurrent.atomic.AtomicLong(5_000_000L);
        service.clock = new java.util.function.LongSupplier() {
            @Override
            public long getAsLong() {
                return now.get();
            }
        };

        renderUntil(service, 1);
        for (int i = 0; i < 5; i++) {
            service.render(ChatItem.builder("stone").build()).get(10, TimeUnit.SECONDS);
        }
        Thread.sleep(100);
        assertEquals(1, service.copyAttempts.get(), "within the minute: not retried per rescan");

        now.addAndGet(BukkitItemImages.COPY_RETRY_MS + 1);
        renderUntil(service, 2);
        assertEquals(2, service.copyAttempts.get(), "a minute later: tried again");
    }

    private static void renderUntil(BukkitItemImages service, int attempts) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (service.copyAttempts.get() < attempts && System.currentTimeMillis() < deadline) {
            service.render(ChatItem.builder("stone").build()).get(10, TimeUnit.SECONDS);
            Thread.sleep(20);
        }
        // Let the failed attempt finish recording its time before the caller looks again.
        Thread.sleep(100);
    }

    @Test
    void aStackRebuildPrunesSupersededServerPacks() throws Exception {
        Files.write(server.resolve("server.properties"), Fixtures.utf8(
                "resource-pack=https\\://packs.example/pack.zip\n"));
        Files.createDirectories(server.resolve("plugins/Heimdall"));
        Files.write(server.resolve("plugins/Heimdall/item-images.yml"),
                Fixtures.utf8("download-vanilla-assets: false\n"));
        Path packs = server.resolve("plugins/Heimdall/cache/packs");
        Files.createDirectories(packs);
        Path superseded = packs.resolve("server-0000000000000000-000000000000.zip");
        Files.write(superseded, Fixtures.zip(Fixtures.files("pack.mcmeta", "{}")));
        Files.setLastModifiedTime(superseded, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 2 * VanillaAssets.LEFTOVER_AGE_MS));
        BukkitItemImages service = build(new Fixtures.FakeHttp().serve(
                "https://packs.example/pack.zip", Fixtures.zip(Fixtures.files(
                        "assets/minecraft/lang/en_us.json",
                        "{\"item.minecraft.stone\":\"Served\"}"))));
        service.rescanNanos = 0L;

        long deadline = System.currentTimeMillis() + 10_000;
        while ((Files.exists(superseded) || !"Served".equals(service.translate("item.minecraft.stone")))
                && System.currentTimeMillis() < deadline) {
            service.render(ChatItem.builder("stone").build()).get(10, TimeUnit.SECONDS);
            Thread.sleep(20);
        }

        assertEquals("Served", service.translate("item.minecraft.stone"), "the new pack is in use");
        assertFalse(Files.exists(superseded), "and the rebuild pruned the old one");
    }

    @Test
    void afterCloseNothingRenders() throws Exception {
        BukkitItemImages service = build(new Fixtures.FakeHttp());
        service.close();
        assertFalse(service.available());
        assertEquals(null, service.render(wardedJar()).get(1, TimeUnit.SECONDS));
    }

    /**
     * A network that takes {@code holdMs} to answer 404 and, like a socket read, ignores interrupts
     * while it does.
     */
    private static final class SlowHttp implements HttpSource {

        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicInteger requests = new AtomicInteger();
        private final long holdMs;
        /** Thrown instead of the 404 once the hold is over, when set. */
        volatile Error thenThrow;

        SlowHttp(long holdMs) {
            this.holdMs = holdMs;
        }

        @Override
        public byte[] get(String url, long maxBytes) throws IOException {
            requests.incrementAndGet();
            entered.countDown();
            deafSleep(holdMs);
            if (thenThrow != null) {
                throw thenThrow;
            }
            throw new IOException("HTTP 404");
        }

        @Override
        public long download(String url, java.nio.file.Path target, long maxBytes)
                throws IOException {
            return get(url, maxBytes).length;
        }
    }

    /** Sleeps {@code ms} and ignores interrupts while it does, like a blocked socket read. */
    private static void deafSleep(long ms) {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
        while (System.nanoTime() < until) {
            Thread.interrupted();
            try {
                Thread.sleep(5);
            } catch (InterruptedException deaf) {
                // Deliberately ignored.
            }
        }
    }

    private static void awaitStopped(java.util.function.BooleanSupplier stopped) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!stopped.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(stopped.getAsBoolean(), "the thread never stopped");
    }

    @Test
    void closeWaitsForBothThreadsAgainstOneDeadline() throws Exception {
        SlowHttp http = new SlowHttp(4_000L);
        BukkitItemImages service = build(http);
        service.closeWaitMs = 1_000L;
        CountDownLatch rendering = new CountDownLatch(1);
        service.runOnRenderThread(() -> {
            rendering.countDown();
            deafSleep(4_000L);
        });
        service.prepare();
        assertTrue(http.entered.await(5, TimeUnit.SECONDS));
        assertTrue(rendering.await(5, TimeUnit.SECONDS));

        long started = System.nanoTime();
        service.close();
        images = null;
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        // One shared deadline: about 1000 ms. One wait per thread would be at least 2000, so the
        // upper bound leaves a loaded runner most of a second. The lower bound cannot flake: a
        // wait is never shorter than asked while both threads are busy.
        assertTrue(took < 1_800L, "close() waited per thread, not once: " + took + "ms");
        assertTrue(took >= 900L, "close() did not wait at all: " + took + "ms");
    }

    @Test
    void theStackIsClosedEvenWhenTheRenderThreadIsStuck() throws Exception {
        BukkitItemImages service = build(new Fixtures.FakeHttp());
        service.render(wardedJar()).get(10, TimeUnit.SECONDS);
        assertTrue(service.stackOpen(), "a render opens the pack stack");
        service.closeWaitMs = 100L;
        CountDownLatch rendering = new CountDownLatch(1);
        service.runOnRenderThread(() -> {
            rendering.countDown();
            deafSleep(1_000L);
        });
        assertTrue(rendering.await(5, TimeUnit.SECONDS));

        service.close();
        images = null;

        assertFalse(service.renderThreadStopped(), "the setup needs the render thread still busy");
        assertFalse(service.stackOpen(), "close() left the pack zips open");
    }

    /** Lets the prepare and its warm-up finish, so the render queue holds only what a test puts there. */
    private static void settle(BukkitItemImages service) throws Exception {
        awaitStopped(() -> service.assetGeneration.get() >= 1);
        service.cachedCards();
    }

    @Test
    void theStackIsClosedWhenTheRenderQueueIsFull() throws Exception {
        BukkitItemImages service = build(new Fixtures.FakeHttp());
        service.render(wardedJar()).get(10, TimeUnit.SECONDS);
        settle(service);
        assertTrue(service.stackOpen());
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch rendering = new CountDownLatch(1);
        service.runOnRenderThread(() -> {
            rendering.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(rendering.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < BukkitItemImages.RENDER_QUEUE; i++) {
            service.runOnRenderThread(() -> { });
        }
        new Thread(() -> {
            deafSleep(150L);
            release.countDown();
        }).start();

        service.close();
        images = null;

        assertTrue(service.renderThreadStopped(), "setup: the render thread finished in time");
        assertFalse(service.stackOpen(), "with no room to queue the close, nobody closed the stack");
    }

    @Test
    void aStackOpenedWhileClosingIsClosedNotInstalled() throws Exception {
        BukkitItemImages service = build(new Fixtures.FakeHttp());
        service.closeWaitMs = 100L;
        CountDownLatch opening = new CountDownLatch(1);
        final BukkitItemImages.StackOpener real = service.opener;
        service.opener = (candidates, vanilla, format) -> {
            opening.countDown();
            deafSleep(600L);
            return real.open(candidates, vanilla, format);
        };
        java.util.concurrent.CompletableFuture<byte[]> drawing = service.render(wardedJar());
        assertTrue(opening.await(5, TimeUnit.SECONDS));

        service.close();
        images = null;
        assertFalse(service.renderThreadStopped(), "setup: the render thread is still opening");
        awaitStopped(service::renderThreadStopped);

        assertFalse(service.stackOpen(),
                "a stack opened during close was installed, and nothing will ever close it");
        assertEquals(null, drawing.get(1, TimeUnit.SECONDS));
    }

    /** Holds a render at {@code step} until the service is closed, and records every step it reaches. */
    private List<String> holdAt(final BukkitItemImages service, final String step,
            final RuntimeException thenThrow, final CountDownLatch reached) {
        final List<String> steps = Collections.synchronizedList(new ArrayList<String>());
        service.renderStep = name -> {
            steps.add(name);
            if (!name.equals(step)) {
                return;
            }
            reached.countDown();
            long until = System.currentTimeMillis() + 5_000;
            while (service.available() && System.currentTimeMillis() < until) {
                deafSleep(5L);
            }
            if (thenThrow != null) {
                throw thenThrow;
            }
        };
        return steps;
    }

    private void assertARenderStopsAt(String step, boolean halfScale) throws Exception {
        BukkitItemImages service = build(new Fixtures.FakeHttp());
        service.render(ChatItem.builder("stone").build()).get(10, TimeUnit.SECONDS);
        settle(service);
        if (halfScale) {
            service.preferredMaxPngBytes = 1;
        }
        CountDownLatch reached = new CountDownLatch(1);
        List<String> steps = holdAt(service, step, null, reached);
        java.util.concurrent.CompletableFuture<byte[]> drawing = service.render(wardedJar());
        assertTrue(reached.await(5, TimeUnit.SECONDS), "never reached " + step + ": " + steps);

        service.close();
        images = null;

        assertEquals(null, drawing.get(5, TimeUnit.SECONDS), "a render carried on past close");
        assertEquals(step, steps.get(steps.size() - 1), "it went on past '" + step + "': " + steps);
    }

    @Test
    void aRenderStopsAfterRefreshingOnceClosed() throws Exception {
        assertARenderStopsAt("refreshed", false);
    }

    @Test
    void aRenderStopsAfterDrawingOnceClosed() throws Exception {
        assertARenderStopsAt("drawn", true);
    }

    @Test
    void aRenderStopsAfterRedrawingOnceClosed() throws Exception {
        assertARenderStopsAt("redrawn", true);
    }

    @Test
    void aRenderFailingAfterCloseSaysNothing() throws Exception {
        BukkitItemImages service = build(new Fixtures.FakeHttp());
        service.render(ChatItem.builder("stone").build()).get(10, TimeUnit.SECONDS);
        settle(service);
        CountDownLatch reached = new CountDownLatch(1);
        holdAt(service, "refreshed", new IllegalStateException("zip file closed"), reached);
        java.util.concurrent.CompletableFuture<byte[]> drawing = service.render(wardedJar());
        assertTrue(reached.await(5, TimeUnit.SECONDS));
        logger.clear();

        service.close();
        images = null;

        assertEquals(null, drawing.get(5, TimeUnit.SECONDS));
        assertFalse(logger.logged(LogLevel.DEBUG, "a render failed"), logger.records().toString());
    }

    @Test
    void aServerPackFetchCutShortByCloseIsNotAWarning() throws Exception {
        Files.write(server.resolve("server.properties"),
                Fixtures.utf8("resource-pack=https\\://packs.example/pack.zip\n"));
        Files.createDirectories(server.resolve("plugins/Heimdall"));
        Files.write(server.resolve("plugins/Heimdall/item-images.yml"),
                Fixtures.utf8("download-vanilla-assets: false\n"));
        SlowHttp http = new SlowHttp(500L);
        BukkitItemImages service = build(http);
        service.closeWaitMs = 50L;
        service.prepare();
        assertTrue(http.entered.await(5, TimeUnit.SECONDS), "the server pack was never fetched");

        service.close();
        images = null;
        awaitStopped(service::assetThreadStopped);

        assertFalse(logger.logged(LogLevel.WARN, "could not fetch the server resource pack"),
                "a stop mid-download is not a failure to retry: " + logger.records());
    }

    @Test
    void workQueuedBehindCloseBuildsNothing() throws Exception {
        BukkitItemImages service = build(new Fixtures.FakeHttp());
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch rendering = new CountDownLatch(1);
        service.runOnRenderThread(() -> {
            rendering.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(rendering.await(5, TimeUnit.SECONDS));
        // Queues the warm-up rebuild behind the blocker once the (failing, offline) prepare ends.
        service.prepare();
        awaitStopped(() -> service.assetGeneration.get() == 1);
        java.util.concurrent.CompletableFuture<byte[]> queued = service.render(wardedJar());
        logger.clear();
        new Thread(() -> {
            deafSleep(150L);
            release.countDown();
        }).start();

        service.close();
        images = null;

        assertTrue(service.renderThreadStopped());
        assertEquals(null, queued.get(1, TimeUnit.SECONDS), "a render queued behind close drew");
        assertFalse(service.stackOpen());
        assertFalse(logger.logged(LogLevel.DEBUG, "pack stack rebuilt"), logger.records().toString());
    }

    @Test
    void anErrorAfterCloseIsDroppedSilently() throws Exception {
        // After a swap's 30 s loader grace, a late asset thread's next class load is a
        // NoClassDefFoundError. Nothing may escape to the thread's uncaught handler (which prints a
        // stack trace into the server log), and nothing may be reported as a failure.
        final List<Throwable> uncaught = Collections.synchronizedList(new ArrayList<Throwable>());
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> uncaught.add(error));
        try {
            SlowHttp http = new SlowHttp(400L);
            http.thenThrow = new NoClassDefFoundError("com/heimdall/Gone");
            BukkitItemImages service = build(http);
            service.closeWaitMs = 50L;
            service.prepare();
            assertTrue(http.entered.await(5, TimeUnit.SECONDS));

            service.close();
            images = null;
            awaitStopped(service::assetThreadStopped);
            Thread.sleep(50);

            assertTrue(uncaught.isEmpty(), "escaped to the uncaught handler: " + uncaught);
            assertFalse(logger.logged(LogLevel.WARN, "preparing the assets failed"),
                    logger.records().toString());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void anErrorBeforeCloseIsReportedWithItsFrames() throws Exception {
        SlowHttp http = new SlowHttp(0L);
        http.thenThrow = new NoClassDefFoundError("com/heimdall/Gone");
        BukkitItemImages service = build(http);
        service.prepare();
        awaitStopped(() -> logger.logged(LogLevel.DEBUG, "where preparing failed"));

        assertTrue(logger.logged(LogLevel.WARN, "preparing the assets failed (java.lang.NoClassDefFoundError)"),
                logger.records().toString());
        assertTrue(logger.logged(LogLevel.DEBUG, "at com.heimdall"), logger.records().toString());
        assertFalse(logger.logged(LogLevel.DEBUG, "com/heimdall/Gone"),
                "a message is never logged, only frames");
    }

    @Test
    void aPackCopyThatOutlivesCloseRebuildsNothing() throws Exception {
        Path generated = server.resolve("plugins/ItemsAdder/output/generated.zip");
        Files.createDirectories(generated.getParent());
        Files.write(generated, Fixtures.zip(Fixtures.files("pack.mcmeta", "{}")));
        Files.createDirectories(server.resolve("plugins/Heimdall"));
        Files.write(server.resolve("plugins/Heimdall/item-images.yml"),
                Fixtures.utf8("download-vanilla-assets: false\n"));
        BukkitItemImages service = build(new Fixtures.FakeHttp());
        service.rescanNanos = 0L;
        service.closeWaitMs = 50L;
        CountDownLatch copying = new CountDownLatch(1);
        service.copier = zip -> {
            copying.countDown();
            deafSleep(500L);
        };

        service.render(ChatItem.builder("stone").build()).get(10, TimeUnit.SECONDS);
        assertTrue(copying.await(5, TimeUnit.SECONDS), "no copy was requested");
        int before = service.assetGeneration.get();

        service.close();
        images = null;
        awaitStopped(service::assetThreadStopped);

        assertEquals(before, service.assetGeneration.get(),
                "the copy finished after close() and published its result anyway");
    }

    @Test
    void closeWaitsForTheAssetThreadBeforeReturning() throws Exception {
        // The paper-swap failure: close() interrupted the asset thread and returned while it was
        // still mid-transfer. The plugin disable finished, Paper closed the plugin's jar, and the
        // thread's next class load failed with "zip file closed". close() must not return while
        // that thread can still run plugin code.
        SlowHttp http = new SlowHttp(400L);
        BukkitItemImages service = build(http);
        service.prepare();
        assertTrue(http.entered.await(5, TimeUnit.SECONDS), "the asset thread never started");

        service.close();
        images = null;

        assertTrue(service.assetThreadStopped(),
                "close() returned with the asset thread still running plugin code");
    }

    @Test
    void aTransferThatOutlivesCloseTouchesNothingAfterIt() throws Exception {
        SlowHttp http = new SlowHttp(500L);
        BukkitItemImages service = build(http);
        service.closeWaitMs = 50L;
        service.prepare();
        assertTrue(http.entered.await(5, TimeUnit.SECONDS));

        long started = System.nanoTime();
        service.close();
        images = null;
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(took < 400L, "close() must stay bounded: " + took + "ms");
        long deadline = System.currentTimeMillis() + 5_000;
        while (!service.assetThreadStopped() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(service.assetThreadStopped());
        assertEquals(0, service.assetGeneration.get(),
                "the asset thread carried on past close() and published its work");
        assertEquals(1, http.requests.get(), "it went on fetching after close()");
        assertFalse(logger.logged(LogLevel.WARN, "preparing the assets failed"),
                logger.records().toString());
        assertFalse(logger.logged(LogLevel.WARN, "could not fetch"),
                "a stop mid-download is not a failure to retry: " + logger.records());
    }

    @Test
    void logsNeverCarryTheItemsText() throws Exception {
        BukkitItemImages service = build(new Fixtures.FakeHttp());
        service.render(wardedJar()).get(10, TimeUnit.SECONDS);
        for (RecordingLogger.Record record : logger.records()) {
            assertFalse(record.message.contains("Warded"), record.message);
            assertFalse(record.message.contains("Throw it"), record.message);
        }
    }
}
