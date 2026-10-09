package com.heimdall.platform.bukkit.itemimage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.items.Snbt;
import com.heimdall.core.log.RecordingLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The vanilla asset cache, against a fake Mojang that serves a tiny client jar. */
class VanillaAssetsTest {

    private static final String VERSION_URL = "https://piston-meta.example/v/1.21.5.json";
    private static final String JAR_URL = "https://piston-data.example/client.jar";

    @TempDir
    Path temp;

    private final RecordingLogger logger = new RecordingLogger(true);
    private final byte[] stick = Fixtures.png(16, 16, 0xFF8B5A2B);

    private byte[] clientJar() {
        return Fixtures.zip(Fixtures.files(
                "version.json", "{\"id\":\"1.21.5\",\"pack_version\":{\"resource\":55,\"data\":71}}",
                "assets/minecraft/textures/item/stick.png", stick,
                "assets/minecraft/models/item/stick.json", "{\"parent\":\"item/handheld\"}",
                "assets/minecraft/items/stick.json", "{\"model\":{\"type\":\"model\"}}",
                "assets/minecraft/lang/en_us.json", "{\"item.minecraft.stick\":\"Stick\"}",
                "assets/minecraft/font/default.json", "{\"providers\":[]}",
                "assets/minecraft/sounds/ambient/cave1.ogg", "not wanted",
                "assets/minecraft/textures/entity/zombie.png", "not wanted",
                "net/minecraft/client/Main.class", "not wanted"));
    }

    /** A fake Mojang serving {@code jar} described as {@code jarSha1}/{@code size}. */
    private Fixtures.FakeHttp mojang(byte[] jar, String jarSha1, long size, boolean badVersionSha) {
        String versionDoc = "{\"id\":\"1.21.5\",\"downloads\":{\"client\":{\"url\":\"" + JAR_URL
                + "\",\"sha1\":\"" + jarSha1 + "\",\"size\":" + size + "}}}";
        byte[] versionBytes = Fixtures.utf8(versionDoc);
        String versionSha = badVersionSha ? repeat('0', 40) : VanillaAssets.sha1(versionBytes);
        String manifest = "{\"latest\":{\"release\":\"1.21.5\"},\"versions\":["
                + "{\"id\":\"25w20a\",\"type\":\"snapshot\",\"url\":\"https://x/snap.json\",\"sha1\":\"x\"},"
                + "{\"id\":\"1.21.5\",\"type\":\"release\",\"url\":\"" + VERSION_URL + "\",\"sha1\":\""
                + versionSha + "\"},"
                + "{\"id\":\"1.21.4\",\"type\":\"release\",\"url\":\"https://x/1.21.4.json\",\"sha1\":\"x\"}]}";
        return new Fixtures.FakeHttp()
                .serve(VanillaAssets.MANIFEST_URL, Fixtures.utf8(manifest))
                .serve(VERSION_URL, versionBytes)
                .serve(JAR_URL, jar);
    }

    private static String repeat(char c, int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) {
            out.append(c);
        }
        return out.toString();
    }

    private List<String> children(Path dir) throws IOException {
        List<String> names = new ArrayList<String>();
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                names.add(child.getFileName().toString());
            }
        }
        return names;
    }

    @Test
    void extractsOnlyWhatATooltipNeedsAndMarksTheCacheComplete() throws Exception {
        byte[] jar = clientJar();
        VanillaAssets assets = new VanillaAssets(logger,
                mojang(jar, VanillaAssets.sha1(jar), jar.length, false), temp);

        Path dir = assets.ensure("1.21.5");

        assertEquals(temp.resolve("1.21.5"), dir);
        assertArrayEquals(stick,
                Files.readAllBytes(dir.resolve("assets/minecraft/textures/item/stick.png")));
        assertTrue(Files.isRegularFile(dir.resolve("assets/minecraft/lang/en_us.json")));
        assertTrue(Files.isRegularFile(dir.resolve("version.json")));
        assertFalse(Files.exists(dir.resolve("assets/minecraft/sounds")));
        assertFalse(Files.exists(dir.resolve("assets/minecraft/textures/entity")));
        assertFalse(Files.exists(dir.resolve("net")));

        String marker = new String(Files.readAllBytes(dir.resolve(VanillaAssets.MARKER)),
                StandardCharsets.UTF_8);
        assertTrue(marker.startsWith(VanillaAssets.sha1(jar)), "the marker holds the jar's SHA-1");
        assertEquals(dir, assets.cached("1.21.5"));
        assertEquals(55, VanillaAssets.packFormat(dir));
        assertEquals(1, children(temp).size(), "the jar and the staging directory are gone");
    }

    @Test
    void aCompleteCacheIsUsedWithoutTheNetwork() throws Exception {
        byte[] jar = clientJar();
        Fixtures.FakeHttp http = mojang(jar, VanillaAssets.sha1(jar), jar.length, false);
        VanillaAssets assets = new VanillaAssets(logger, http, temp);
        assets.ensure("1.21.5");
        http.requests.clear();

        assertNotNull(assets.ensure("1.21.5"));
        assertTrue(http.requests.isEmpty());
    }

    @Test
    void aJarWhoseSha1DoesNotMatchIsRejectedAndLeavesNothing() throws Exception {
        byte[] jar = clientJar();
        VanillaAssets assets = new VanillaAssets(logger,
                mojang(jar, repeat('a', 40), jar.length, false), temp);

        IOException failure = assertThrows(IOException.class, () -> assets.ensure("1.21.5"));
        assertTrue(failure.getMessage().contains("SHA-1"));
        assertNull(assets.cached("1.21.5"));
        assertTrue(children(temp).isEmpty(), "no jar, no staging, no cache: " + children(temp));
    }

    @Test
    void aVersionDocumentWhoseSha1DoesNotMatchIsRejected() {
        byte[] jar = clientJar();
        VanillaAssets assets = new VanillaAssets(logger,
                mojang(jar, VanillaAssets.sha1(jar), jar.length, true), temp);

        assertThrows(IOException.class, () -> assets.ensure("1.21.5"));
        assertNull(assets.cached("1.21.5"));
    }

    @Test
    void aJarOfTheWrongSizeIsRejected() {
        byte[] jar = clientJar();
        VanillaAssets assets = new VanillaAssets(logger,
                mojang(jar, VanillaAssets.sha1(jar), jar.length + 10, false), temp);

        assertThrows(IOException.class, () -> assets.ensure("1.21.5"));
        assertNull(assets.cached("1.21.5"));
    }

    @Test
    void aPartialCacheIsNeverUsedAndIsRebuilt() throws Exception {
        Path partial = Fixtures.write(temp.resolve("1.21.5"), Fixtures.files(
                "assets/minecraft/textures/item/stick.png", Fixtures.png(16, 16, 0xFFFF0000)));
        byte[] jar = clientJar();
        VanillaAssets assets = new VanillaAssets(logger,
                mojang(jar, VanillaAssets.sha1(jar), jar.length, false), temp);

        assertNull(assets.cached("1.21.5"), "no marker, no cache");
        Path dir = assets.ensure("1.21.5");

        assertEquals(partial, dir);
        assertArrayEquals(stick,
                Files.readAllBytes(dir.resolve("assets/minecraft/textures/item/stick.png")),
                "the partial file was replaced, not trusted");
    }

    @Test
    void aMarkerThatIsNotASha1DoesNotCount() throws Exception {
        Fixtures.write(temp.resolve("1.21.5"), Fixtures.files(VanillaAssets.MARKER, "half"));
        assertNull(new VanillaAssets(logger, new Fixtures.FakeHttp(), temp).cached("1.21.5"));
    }

    @Test
    void anUnlistedVersionUsesTheNearestOlderRelease() throws Exception {
        Map<String, Object> manifest = Snbt.asMap(Snbt.parse("{versions:["
                + "{id:\"26.1\",type:\"release\"},"
                + "{id:\"1.21.7-pre1\",type:\"snapshot\"},"
                + "{id:\"1.21.6\",type:\"release\"},"
                + "{id:\"1.21.5\",type:\"release\"},"
                + "{id:\"1.21\",type:\"release\"},"
                + "{id:\"1.8.8\",type:\"release\"}]}"));
        assertEquals("1.21.6", VanillaAssets.pickVersion(manifest, "1.21.6").get("id"));
        assertEquals("1.21.6", VanillaAssets.pickVersion(manifest, "1.21.8").get("id"));
        assertEquals("1.21", VanillaAssets.pickVersion(manifest, "1.21.1").get("id"));
        assertEquals("26.1", VanillaAssets.pickVersion(manifest, "26.1.2").get("id"));
        assertEquals("1.21.6", VanillaAssets.pickVersion(manifest, "25.4").get("id"),
                "a year version is newer than every 1.x");
        assertEquals("1.8.8", VanillaAssets.pickVersion(manifest, "1.8.9").get("id"));
        assertNull(VanillaAssets.pickVersion(manifest, "1.7.10"));
        assertArrayEquals(new int[] {26, 1, 0}, VanillaAssets.parseVersion("26.1"));
        assertNull(VanillaAssets.parseVersion("1.21.5-pre1"));
    }

    @Test
    void aJarEntryThatEscapesTheCacheFailsTheExtraction() throws Exception {
        byte[] evil = Fixtures.zip(Fixtures.files(
                "assets/minecraft/models/../../../../escaped.json", "{}"));
        Path jar = temp.resolve("evil.jar");
        Files.write(jar, evil);
        assertThrows(IOException.class, () -> VanillaAssets.extract(jar, temp.resolve("out")));
        assertFalse(Files.exists(temp.getParent().resolve("escaped.json")));
    }

    @Test
    void versionStringsFromBukkit() {
        assertEquals("1.21.5", BukkitItemImages.versionOf("1.21.5-R0.1-SNAPSHOT"));
        assertEquals("26.1", BukkitItemImages.versionOf("26.1-R0.1-SNAPSHOT"));
        assertEquals("1.8.8", BukkitItemImages.versionOf("1.8.8"));
    }
}
