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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
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
    void afterCloseNothingRenders() throws Exception {
        BukkitItemImages service = build(new Fixtures.FakeHttp());
        service.close();
        assertFalse(service.available());
        assertEquals(null, service.render(wardedJar()).get(1, TimeUnit.SECONDS));
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
