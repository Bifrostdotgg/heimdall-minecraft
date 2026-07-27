package com.heimdall.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The write-then-replace helper both persistent stores are built on. */
class AtomicFilesTest {

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    void writesANewFile(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("out.txt");

        AtomicFiles.writeUtf8(target, "hello");

        assertEquals("hello", read(target));
    }

    @Test
    void replacesAnExistingFileEntirely(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("out.txt");
        AtomicFiles.writeUtf8(target, "a much longer original body");

        AtomicFiles.writeUtf8(target, "short");

        assertEquals("short", read(target), "a partial overwrite would leave the tail behind");
    }

    @Test
    void createsMissingParentDirectories(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("a").resolve("b").resolve("out.txt");

        AtomicFiles.writeUtf8(target, "nested");

        assertEquals("nested", read(target));
    }

    @Test
    @DisplayName("no temp file survives a successful write")
    void leavesNoTempFiles(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("out.txt");
        for (int i = 0; i < 5; i++) {
            AtomicFiles.writeUtf8(target, "body " + i);
        }

        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).collect(Collectors.toList());
            assertEquals(1, names.size(), "temp litter accumulates on every save: " + names);
        }
    }

    @Test
    void roundTripsNonAsciiAsUtf8(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("out.txt");

        AtomicFiles.writeUtf8(target, "§cDenied — naïve");

        assertEquals("§cDenied — naïve", read(target));
        assertTrue(Files.size(target) > "§cDenied — naïve".length(),
                "the multi-byte characters should really be UTF-8 encoded");
    }
}
