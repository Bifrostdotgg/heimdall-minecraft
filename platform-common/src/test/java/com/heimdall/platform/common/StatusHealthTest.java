package com.heimdall.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.json.Payload;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The wire shape of MOTD and favicon: stripping, the 64 KiB cap, and no data-URI prefix.
 */
class StatusHealthTest {

    @Test
    @DisplayName("an empty or missing MOTD still sends motdClean as an empty string")
    void emptyMotdIsPresentAndEmpty() {
        Payload empty = StatusHealth.apply(Payload.builder(), "", null).build();
        assertEquals("", empty.string("motdClean", "missing"));
        assertFalse(empty.has("motdRaw"));
        assertFalse(empty.has("iconPngBase64"));

        Payload missing = StatusHealth.apply(Payload.builder(), null, null).build();
        assertEquals("", missing.string("motdClean", "missing"));
        assertFalse(missing.has("motdRaw"));
    }

    @Test
    @DisplayName("section-sign colour and format codes are stripped for motdClean")
    void sectionCodesAreStripped() {
        Payload snapshot = StatusHealth.apply(
                Payload.builder(), "\u00A7cHello \u00A7lWorld", null).build();
        assertEquals("Hello World", snapshot.string("motdClean", ""));
        assertEquals("\u00A7cHello \u00A7lWorld", snapshot.string("motdRaw", ""));
    }

    @Test
    @DisplayName("hex colour runs and leftover section signs are stripped")
    void hexAndLeftoverSectionSignsAreStripped() {
        assertEquals("Hi", StatusHealth.clean("\u00A7x\u00A7f\u00A7f\u00A70\u00A70\u00A70\u00A70Hi"));
        assertEquals("Hello", StatusHealth.clean("Hello\u00A7"));
    }

    @Test
    @DisplayName("MiniMessage tags are stripped for motdClean")
    void miniMessageTagsAreStripped() {
        assertEquals("Hello", StatusHealth.clean("<red>Hello</red>"));
        assertEquals("Hi there", StatusHealth.clean("<gradient:red:blue>Hi</gradient> there"));
    }

    @Test
    @DisplayName("a PNG of exactly 64 KiB is accepted")
    void exactCapIsAccepted() {
        byte[] png = new byte[StatusHealth.MAX_ICON_BYTES];
        png[0] = 1;
        String encoded = StatusHealth.encodeIcon(png);
        assertNotNull(encoded);
        assertFalse(encoded.startsWith("data:"), "the wire form is raw standard base64");
        assertEquals(png.length, Base64.getDecoder().decode(encoded).length);

        Payload snapshot = StatusHealth.apply(Payload.builder(), "", png).build();
        assertEquals(encoded, snapshot.string("iconPngBase64", ""));
    }

    @Test
    @DisplayName("a PNG one byte over 64 KiB is dropped")
    void oneByteOverCapIsDropped() {
        byte[] png = new byte[StatusHealth.MAX_ICON_BYTES + 1];
        assertNull(StatusHealth.encodeIcon(png));
        Payload snapshot = StatusHealth.apply(Payload.builder(), "", png).build();
        assertFalse(snapshot.has("iconPngBase64"));
    }

    @Test
    @DisplayName("null and empty icon bytes are missing, not an empty string")
    void missingIconIsOmitted() {
        assertNull(StatusHealth.encodeIcon(null));
        assertNull(StatusHealth.encodeIcon(new byte[0]));
    }

    @Test
    @DisplayName("a data-URI is decoded, capped, and re-encoded without the prefix")
    void dataUriIsStrippedAndRecoded() {
        byte[] png = "png-bytes".getBytes(StandardCharsets.US_ASCII);
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        byte[] decoded = StatusHealth.decodeIcon(dataUri);
        assertNotNull(decoded);
        assertEquals("png-bytes", new String(decoded, StandardCharsets.US_ASCII));
        assertFalse(StatusHealth.encodeIcon(decoded).startsWith("data:"));
    }

    @Test
    @DisplayName("an oversize payload hidden inside a data-URI is dropped")
    void oversizeDataUriIsDropped() {
        byte[] png = new byte[StatusHealth.MAX_ICON_BYTES + 1];
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        assertNull(StatusHealth.decodeIcon(dataUri));
    }

    @Test
    @DisplayName("the file cache re-reads only after mtime changes")
    void iconFileCacheFollowsMtime(@TempDir File dir) throws IOException {
        File icon = new File(dir, "server-icon.png");
        Files.write(icon.toPath(), "first".getBytes(StandardCharsets.US_ASCII));
        long firstMtime = icon.lastModified();
        StatusHealth.IconFile cache = new StatusHealth.IconFile(icon);

        assertEquals("first", new String(cache.read(), StandardCharsets.US_ASCII));
        Files.write(icon.toPath(), "second".getBytes(StandardCharsets.US_ASCII));
        assertTrue(icon.setLastModified(firstMtime), "pin mtime so the cache must not notice yet");
        assertEquals("first", new String(cache.read(), StandardCharsets.US_ASCII),
                "same mtime must not go back to disk");

        assertTrue(icon.setLastModified(firstMtime + 2000L));
        assertEquals("second", new String(cache.read(), StandardCharsets.US_ASCII));
    }
}
