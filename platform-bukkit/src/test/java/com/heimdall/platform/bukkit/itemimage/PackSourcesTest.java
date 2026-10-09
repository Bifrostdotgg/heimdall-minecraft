package com.heimdall.platform.bukkit.itemimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.RecordingLogger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pack discovery, priority, change detection and the server.properties pack. */
class PackSourcesTest {

    @TempDir
    Path server;

    private final RecordingLogger logger = new RecordingLogger(true);

    private Path plugins() {
        return server.resolve("plugins");
    }

    private PackSources sources() {
        return new PackSources(logger, server, plugins(),
                plugins().resolve("Heimdall").resolve("cache"));
    }

    private static byte[] pack(String marker) {
        return Fixtures.zip(Fixtures.files("pack.mcmeta", "{\"pack\":{\"pack_format\":55}}",
                "assets/minecraft/lang/en_us.json", "{\"marker\":\"" + marker + "\"}"));
    }

    private static List<String> labels(List<PackSources.Candidate> candidates) {
        List<String> out = new ArrayList<String>();
        for (PackSources.Candidate candidate : candidates) {
            out.add(candidate.label + ":" + candidate.path.getFileName());
        }
        return out;
    }

    @Test
    void discoversEverySourceInPriorityOrder() throws Exception {
        Path folder = plugins().resolve("Heimdall/item-images/packs");
        Files.createDirectories(folder);
        Files.write(folder.resolve("b.zip"), pack("folder-b"));
        Files.write(folder.resolve("a.zip"), pack("folder-a"));
        Fixtures.write(folder.resolve("unpacked"), Fixtures.files("pack.mcmeta", "{}"));
        Path serverPack = plugins().resolve("Heimdall/cache/packs/server-x.zip");
        Files.createDirectories(serverPack.getParent());
        Files.write(serverPack, pack("server"));
        Path generated = plugins().resolve("ItemsAdder/output/generated.zip");
        Files.createDirectories(generated.getParent());
        Files.write(generated, pack("itemsadder"));
        Files.createDirectories(plugins().resolve("Nexo/pack"));
        Files.write(plugins().resolve("Nexo/pack/pack.zip"), pack("nexo"));
        Files.createDirectories(plugins().resolve("Oraxen/pack"));
        Files.write(plugins().resolve("Oraxen/pack/pack.zip"), pack("oraxen"));
        Files.write(plugins().resolve("Oraxen/pack/notes.txt"), new byte[] {1});

        List<PackSources.Candidate> found = sources().discover(folder, serverPack);

        List<String> expected = new ArrayList<String>();
        expected.add("folder:a.zip");
        expected.add("folder:b.zip");
        expected.add("folder:unpacked");
        expected.add("server:server-x.zip");
        expected.add("itemsadder:generated.zip");
        expected.add("nexo:pack.zip");
        expected.add("oraxen:pack.zip");
        assertEquals(expected, labels(found));

        PackStack stack = sources().open(found, null, 55);
        try {
            String lang = new String(stack.read("assets/minecraft/lang/en_us.json", 1024), "UTF-8");
            assertTrue(lang.contains("folder-a"), "the operator's folder wins");
        } finally {
            stack.close();
        }
    }

    @Test
    void packZipsAreOpenedFromPrivateCopiesSoTheOriginalsStayFree() throws Exception {
        Path generated = plugins().resolve("ItemsAdder/output/generated.zip");
        Files.createDirectories(generated.getParent());
        Files.write(generated, pack("itemsadder"));
        PackSources sources = sources();

        PackStack stack = sources.open(sources.discover(null, null), null, 55);
        try {
            Files.delete(generated);
            Files.write(generated, pack("rebuilt"));
            String lang = new String(stack.read("assets/minecraft/lang/en_us.json", 1024), "UTF-8");
            assertTrue(lang.contains("itemsadder"), "the open stack reads its own copy");
            assertEquals(1, stack.copies().size());
        } finally {
            stack.close();
        }

        PackStack rebuilt = sources.open(sources.discover(null, null), null, 55);
        try {
            sources.pruneCopies(rebuilt.copies());
            Path open = plugins().resolve("Heimdall/cache/packs/open");
            assertEquals(1, Files.list(open).count(), "the superseded copy is gone");
        } finally {
            rebuilt.close();
        }
    }

    @Test
    void anUnhashedServerPackIsRefetchedOnceStale() throws Exception {
        Files.write(server.resolve("server.properties"), Fixtures.utf8(
                "resource-pack=https\\://packs.example/pack.zip\n"));
        Fixtures.FakeHttp http = new Fixtures.FakeHttp()
                .serve("https://packs.example/pack.zip", pack("v1"));
        Path first = sources().serverPack(http);
        assertEquals(first, sources().serverPack(http));
        assertEquals(1, http.requests.size(), "fresh: not refetched");

        Files.setLastModifiedTime(first, FileTime.fromMillis(System.currentTimeMillis()
                - 2 * PackSources.UNHASHED_PACK_MAX_AGE_MS));
        byte[] v2 = pack("v2");
        http.serve("https://packs.example/pack.zip", v2);
        Path again = sources().serverPack(http);

        assertEquals(2, http.requests.size(), "a day old with nothing to pin it: refetched");
        assertTrue(java.util.Arrays.equals(v2, Files.readAllBytes(again)));
    }

    @Test
    void aProtectedItemsAdderZipFallsBackToTheUncompressedOutput() throws Exception {
        Path generated = plugins().resolve("ItemsAdder/output/generated.zip");
        Files.createDirectories(generated.getParent());
        Files.write(generated, new byte[] {'P', 'K', 3, 4, 0, 0, 1, 2, 3});
        Fixtures.write(plugins().resolve("ItemsAdder/output_uncompressed"), Fixtures.files(
                "assets/minecraft/items/heart_of_the_sea.json", "{}"));

        List<PackSources.Candidate> found = sources().discover(null, null);

        assertEquals(1, found.size());
        assertEquals("output_uncompressed", found.get(0).path.getFileName().toString());
        assertFalse(found.get(0).zip);
    }

    @Test
    void theFingerprintChangesWhenAPackIsRegenerated() throws Exception {
        Path generated = plugins().resolve("ItemsAdder/output/generated.zip");
        Files.createDirectories(generated.getParent());
        Files.write(generated, pack("one"));
        Files.setLastModifiedTime(generated, FileTime.fromMillis(1_000_000L));
        String before = PackSources.fingerprint(sources().discover(null, null), null);

        Files.write(generated, pack("two-longer"));
        Files.setLastModifiedTime(generated, FileTime.fromMillis(2_000_000L));
        String after = PackSources.fingerprint(sources().discover(null, null), null);

        assertNotEquals(before, after);
        assertEquals(after, PackSources.fingerprint(sources().discover(null, null), null),
                "nothing changed, nothing to rebuild");
    }

    @Test
    void theServerPropertiesPackIsDownloadedOnceAndCheckedAgainstItsSha1() throws Exception {
        byte[] body = pack("server");
        Files.write(server.resolve("server.properties"), Fixtures.utf8(
                "motd=hi\nresource-pack=https\\://packs.example/pack.zip\nresource-pack-sha1="
                        + VanillaAssets.sha1(body) + "\n"));
        Fixtures.FakeHttp http = new Fixtures.FakeHttp().serve("https://packs.example/pack.zip", body);

        Path first = sources().serverPack(http);
        Path second = sources().serverPack(http);

        assertNotNull(first);
        assertEquals(first, second);
        assertEquals(1, http.requests.size(), "cached by URL and hash, not refetched");

        // A new URL replaces the old download rather than accumulating beside it.
        Files.write(server.resolve("server.properties"), Fixtures.utf8(
                "resource-pack=https\\://packs.example/v2.zip\n"));
        http.serve("https://packs.example/v2.zip", pack("v2"));
        Path third = sources().serverPack(http);
        assertFalse(Files.exists(first));
        assertTrue(Files.isRegularFile(third));
    }

    @Test
    void aServerPackThatDoesNotMatchItsSha1IsRefused() throws Exception {
        Files.write(server.resolve("server.properties"), Fixtures.utf8(
                "resource-pack=https\\://packs.example/pack.zip\nresource-pack-sha1="
                        + "0000000000000000000000000000000000000000\n"));
        Fixtures.FakeHttp http = new Fixtures.FakeHttp()
                .serve("https://packs.example/pack.zip", pack("tampered"));

        assertThrows(IOException.class, () -> sources().serverPack(http));
        Path packs = plugins().resolve("Heimdall/cache/packs");
        assertTrue(!Files.exists(packs) || !Files.list(packs).findAny().isPresent(),
                "nothing is left behind");
    }

    @Test
    void noResourcePackMeansNothingToFetch() throws Exception {
        Files.write(server.resolve("server.properties"), Fixtures.utf8("resource-pack=\n"));
        assertNull(sources().serverPack(new Fixtures.FakeHttp()));
    }
}
