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
