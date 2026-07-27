package com.heimdall.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.RecordingLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading a flag out of a file another program owns.
 *
 * <p>The behaviour that matters is what happens when the read goes wrong, because this is pointed
 * at {@code spigot.yml} and {@code paper-global.yml} — files the server rewrites on its own
 * schedule and an operator edits by hand. Every failure has to be the fallback, not an exception on
 * a boot path.
 */
class YamlProbeTest {

    private final RecordingLogger logger = new RecordingLogger();

    private static Path write(Path directory, String name, String content) throws IOException {
        Path file = directory.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    @DisplayName("a nested boolean is found by its dotted path")
    void nestedBoolean(@TempDir Path dir) throws IOException {
        Path file = write(dir, "spigot.yml",
                "settings:\n  bungeecord: true\n  sample-count: 12\n");
        assertTrue(YamlProbe.flag(file, "settings.bungeecord", false, logger));
    }

    @Test
    @DisplayName("three levels deep works the same way")
    void deeplyNested(@TempDir Path dir) throws IOException {
        Path file = write(dir, "paper.yml",
                "settings:\n  velocity-support:\n    enabled: true\n    online-mode: false\n");
        assertTrue(YamlProbe.flag(file, "settings.velocity-support.enabled", false, logger));
        assertFalse(YamlProbe.flag(file, "settings.velocity-support.online-mode", true, logger));
    }

    @Test
    @DisplayName("a quoted boolean is still a boolean")
    void quotedBoolean(@TempDir Path dir) throws IOException {
        // SnakeYAML hands a quoted value back as a String, which would otherwise silently be false.
        Path file = write(dir, "config.yml", "settings:\n  bungeecord: \"true\"\n");
        assertTrue(YamlProbe.flag(file, "settings.bungeecord", false, logger));
    }

    @Test
    @DisplayName("yes/no and on/off are booleans too, whichever way SnakeYAML reads them")
    void yamlBooleanSpellings(@TempDir Path dir) throws IOException {
        assertTrue(YamlProbe.flag(
                write(dir, "a.yml", "flag: yes\n"), "flag", false, logger));
        assertTrue(YamlProbe.flag(
                write(dir, "b.yml", "flag: \"on\"\n"), "flag", false, logger));
        assertFalse(YamlProbe.flag(
                write(dir, "c.yml", "flag: \"off\"\n"), "flag", true, logger));
    }

    @Test
    @DisplayName("an absent file, key or branch is the fallback")
    void absencesAreTheFallback(@TempDir Path dir) throws IOException {
        Path file = write(dir, "spigot.yml", "settings:\n  bungeecord: true\n");

        assertTrue(YamlProbe.flag(dir.resolve("nope.yml"), "settings.bungeecord", true, logger));
        assertTrue(YamlProbe.flag(file, "settings.missing", true, logger));
        assertTrue(YamlProbe.flag(file, "missing.branch.entirely", true, logger));
        assertTrue(YamlProbe.flag(null, "settings.bungeecord", true, logger));
        assertTrue(YamlProbe.flag(file, "", true, logger));
    }

    @Test
    @DisplayName("a path that runs into a scalar stops rather than throwing")
    void walkingPastALeaf(@TempDir Path dir) throws IOException {
        Path file = write(dir, "spigot.yml", "settings:\n  bungeecord: true\n");
        assertNull(YamlProbe.value(file, "settings.bungeecord.deeper", logger));
    }

    @Test
    @DisplayName("malformed YAML is the fallback, not an exception")
    void malformed(@TempDir Path dir) throws IOException {
        Path file = write(dir, "spigot.yml", "settings:\n  bungeecord: [unclosed\n");
        assertTrue(YamlProbe.flag(file, "settings.bungeecord", true, logger));
        assertFalse(YamlProbe.flag(file, "settings.bungeecord", false, logger));
    }

    @Test
    @DisplayName("a value that is neither boolean nor boolean-ish is the fallback")
    void nonBooleanValue(@TempDir Path dir) throws IOException {
        Path file = write(dir, "spigot.yml", "settings:\n  bungeecord: maybe\n");
        assertTrue(YamlProbe.flag(file, "settings.bungeecord", true, logger));
        assertFalse(YamlProbe.flag(file, "settings.bungeecord", false, logger));
    }

    @Test
    @DisplayName("value() hands back what is there, so a caller can read more than booleans")
    void rawValue(@TempDir Path dir) throws IOException {
        Path file = write(dir, "server.yml", "network:\n  name: survival\n  port: 25565\n");
        assertEquals("survival", YamlProbe.value(file, "network.name", logger));
        assertEquals(Integer.valueOf(25565), YamlProbe.value(file, "network.port", logger));
    }

    @Test
    @DisplayName("an empty document is absence, not a crash")
    void emptyDocument(@TempDir Path dir) throws IOException {
        Path file = write(dir, "empty.yml", "# nothing but a comment\n");
        assertNull(YamlProbe.value(file, "anything", logger));
        assertFalse(YamlProbe.flag(file, "anything", false, logger));
    }

    @Test
    @DisplayName("a null logger is silence, not a crash")
    void nullLogger(@TempDir Path dir) throws IOException {
        Path file = write(dir, "spigot.yml", "settings:\n  bungeecord: [unclosed\n");
        assertFalse(YamlProbe.flag(file, "settings.bungeecord", false, null));
    }
}
