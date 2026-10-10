package com.heimdall.shell.hotswap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.shell.contract.ShellContract;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading, checking and writing cores, without loading a single class from them. */
class CoreArchiveTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a release's embedded core is read, checked against core.properties and written once")
    void extractsAndReuses() throws Exception {
        Path release = Fixtures.release(dir, "alpha", "1.0.0-alpha", ShellContract.VERSION, null);

        CoreArchive.Embedded embedded = CoreArchive.readEmbedded(release);
        Path first = CoreArchive.materialise(embedded, dir.resolve("core"));
        long modified = Files.getLastModifiedTime(first).toMillis();
        Path second = CoreArchive.materialise(embedded, dir.resolve("core"));

        assertEquals(first, second);
        assertEquals(modified, Files.getLastModifiedTime(second).toMillis(), "rewritten needlessly");
        assertEquals(Sha256.of(Fixtures.core("alpha")), Sha256.of(first));
        assertTrue(first.getFileName().toString().startsWith("heimdall-core-1.0.0-alpha-"));
        assertTrue(CoreArchive.isRelease(release));
        assertFalse(CoreArchive.isRelease(Fixtures.core("alpha")));
    }

    @Test
    @DisplayName("an embedded core that does not match its recorded hash is refused as damaged")
    void mismatchedHashIsRefused() throws Exception {
        Path release = Fixtures.release(dir, "alpha", "1.0.0-alpha", ShellContract.VERSION,
                "1111111111111111111111111111111111111111111111111111111111111111");

        CoreArchiveException refused =
                assertThrows(CoreArchiveException.class, () -> CoreArchive.readEmbedded(release));
        assertTrue(refused.getMessage().contains("damaged"), refused.getMessage());
    }

    @Test
    @DisplayName("describe reads the manifest and the services entry, and refuses a non-core jar")
    void describe() throws Exception {
        CoreArchive.CoreJar alpha = CoreArchive.describe(Fixtures.core("alpha"));
        assertEquals("1.0.0-alpha", alpha.identity().version());
        assertEquals(ShellContract.VERSION, alpha.contract());
        assertEquals("com.heimdall.fixture.FixtureCore", alpha.entryClass());

        Path notACore = dir.resolve("other-plugin.jar");
        ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(notACore));
        out.putNextEntry(new ZipEntry("plugin.yml"));
        out.write("name: Other\n".getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
        out.close();
        CoreArchiveException refused =
                assertThrows(CoreArchiveException.class, () -> CoreArchive.describe(notACore));
        assertTrue(refused.getMessage().contains("not a Heimdall core"), refused.getMessage());
    }

    @Test
    @DisplayName("file names are content-addressed and safe whatever the version says")
    void fileNames() {
        String sha = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        assertEquals("heimdall-core-3.1.0-abcdef012345.jar", CoreArchive.fileName("3.1.0", sha));
        assertEquals("heimdall-core-.._.._x-abcdef012345.jar",
                CoreArchive.fileName("../../x", sha));
    }

    @Test
    @DisplayName("prune keeps the named core and removes the others")
    void prune() throws Exception {
        Path coreDir = Files.createDirectories(dir.resolve("core"));
        Path keep = Files.write(coreDir.resolve("heimdall-core-1-aaaaaaaaaaaa.jar"), new byte[] {1});
        Files.write(coreDir.resolve("heimdall-core-0-bbbbbbbbbbbb.jar"), new byte[] {2});
        Files.write(coreDir.resolve("staged.jar"), new byte[] {3});

        int removed = CoreArchive.prune(coreDir,
                Collections.singleton(keep.toAbsolutePath().normalize()), new RecordingShellLog());

        assertEquals(1, removed);
        assertTrue(Files.exists(keep));
        assertTrue(Files.exists(coreDir.resolve("staged.jar")), "an operator's staged file is theirs");
    }

    @Test
    @DisplayName("SHA-256 well-formedness is strict: 64 lowercase hex, nothing else")
    void wellFormed() {
        assertTrue(Sha256.isWellFormed(Sha256.of(new byte[0])));
        assertFalse(Sha256.isWellFormed(Sha256.of(new byte[0]).toUpperCase()));
        assertFalse(Sha256.isWellFormed("sha256:" + Sha256.of(new byte[0])));
        assertFalse(Sha256.isWellFormed(null));
    }
}
