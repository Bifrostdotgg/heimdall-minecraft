package com.heimdall.core.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Adam's ChatControl captures from Third Place (Paper 1.21.5+, ChatControl 12.2.18, plugin rc.14),
 * exactly as {@code ChannelPostChatEvent#getMessage()} returned them for a player using
 * {@code [item]}.
 *
 * <p>Kept as resource files rather than Java string literals so they stay byte-for-byte what was
 * captured: a literal would need every quote escaped, and a test that tidied the input would be
 * testing a format nobody sends. Shared through test fixtures because core's parser tests and the
 * bridge's wire tests both need the same two lines.
 */
public final class ItemCaptures {

    private ItemCaptures() {
    }

    /** A netherite shovel named "Spoon": enchanted (one custom enchantment), unbreakable, hidden. */
    public static String spoon() {
        return load("spoon.txt");
    }

    /** An ItemsAdder heart of the sea, "Warded Jar": custom model data, four lore lines, 52/64 damage. */
    public static String wardedJar() {
        return load("warded_jar.txt");
    }

    private static String load(String name) {
        String path = "/heimdall-test/items/" + name;
        try (InputStream in = ItemCaptures.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
