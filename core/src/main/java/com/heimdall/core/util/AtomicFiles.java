package com.heimdall.core.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Whole-file replacement that a crash cannot leave half-done.
 *
 * <p>Every persistent file core owns — the bootstrap config, the mirror — is rewritten in full on
 * each save. Writing straight over the live file means a JVM kill (or a server box losing power)
 * mid-write leaves a truncated file that fails to parse on the next boot, and the plugin comes up
 * with an empty whitelist mirror or an unconfigured endpoint. v2 wrote the mirror that way, with a
 * plain {@code FileWriter}, on nearly every mutation.
 *
 * <p>So the bytes go to a sibling temp file first and only then replace the target. The move is
 * requested as {@link StandardCopyOption#ATOMIC_MOVE} and falls back to a plain replace and then a
 * copy — the temp file is created in the target's own directory precisely so the atomic path is
 * the one normally taken, but a server data directory can be a bind mount, an overlay, or a
 * network share where the filesystem refuses it, and refusing to write at all there would be
 * worse than a non-atomic write.
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /** Writes UTF-8 text, replacing any existing file. */
    public static void writeUtf8(Path target, String content) throws IOException {
        write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes bytes to {@code target}, replacing any existing file, without ever leaving the target
     * partially written.
     *
     * @throws IOException if the temp file could not be written or could not replace the target
     */
    public static void write(Path target, byte[] content) throws IOException {
        Path parent = target.getParent() == null ? Paths.get(".") : target.getParent();
        Files.createDirectories(parent);

        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, content);
            replace(temp, target);
        } finally {
            // A successful move already consumed the temp file; this only matters on the failure
            // paths, where leaving .tmp litter behind would accumulate on every failed save.
            Files.deleteIfExists(temp);
        }
    }

    private static void replace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (AtomicMoveNotSupportedException e) {
            // Falls through: the filesystem cannot promise atomicity, but it can still move.
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Last resort for a cross-device rename the JDK will not do for us.
            Files.copy(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
