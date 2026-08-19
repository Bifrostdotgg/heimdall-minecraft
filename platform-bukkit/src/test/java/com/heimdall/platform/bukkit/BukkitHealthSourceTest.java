package com.heimdall.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.json.Payload;
import com.heimdall.platform.bukkit.adapter.TickSource;
import com.heimdall.platform.common.StatusHealth;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a health snapshot says when it cannot measure something.
 *
 * <p>The rule under test is the one that would otherwise be invisible: a field that cannot be
 * measured is <strong>omitted</strong>, never sent as zero. A Spigot with no tick API reporting
 * {@code tps: 0} would have the dashboard charting a perfectly healthy server as one that had
 * stopped ticking, and the graph would be indistinguishable from a real outage.
 *
 * <p>These run with no Bukkit server behind them, which exercises the other half of the contract
 * for free: the player counts are also left out rather than becoming an exception on the heartbeat
 * thread.
 */
class BukkitHealthSourceTest {

    /** A tick source that reports exactly what it is told to. */
    private static TickSource ticks(final Double tps, final Double mspt) {
        return new TickSource() {
            @Override
            public boolean isAvailable() {
                return tps != null;
            }

            @Override
            public Double tps() {
                return tps;
            }

            @Override
            public Double mspt() {
                return mspt;
            }

            @Override
            public String describe() {
                return "test";
            }
        };
    }

    @Test
    @DisplayName("memory is always reported — it is a JVM question, not a server one")
    void memoryIsAlwaysThere() {
        Payload snapshot = new BukkitHealthSource(TickSource.UNAVAILABLE).snapshot();
        assertTrue(snapshot.has("usedMemMb"));
        assertTrue(snapshot.has("maxMemMb"));
        assertTrue(snapshot.longValue("maxMemMb", 0L) > 0L, "a JVM always has a heap ceiling");
    }

    @Test
    @DisplayName("a server that cannot report a tick rate sends no tps field at all")
    void unavailableTicksAreOmitted() {
        Payload snapshot = new BukkitHealthSource(TickSource.UNAVAILABLE).snapshot();
        assertFalse(snapshot.has("tps"), "an unmeasurable tps must be absent, never zero");
        assertFalse(snapshot.has("mspt"));
    }

    @Test
    @DisplayName("a source with tps but no mspt sends one field, not two")
    void partialTicks() {
        Payload snapshot = new BukkitHealthSource(ticks(19.98, null)).snapshot();
        assertEquals(19.98, snapshot.doubleValue("tps", -1.0), 0.0001);
        assertFalse(snapshot.has("mspt"), "old Paper reports tps and not mspt; say so by omission");
    }

    @Test
    @DisplayName("both fields survive when both can be measured")
    void fullTicks() {
        Payload snapshot = new BukkitHealthSource(ticks(20.0, 4.21)).snapshot();
        assertEquals(20.0, snapshot.doubleValue("tps", -1.0), 0.0001);
        assertEquals(4.21, snapshot.doubleValue("mspt", -1.0), 0.0001);
    }

    @Test
    @DisplayName("no server behind it means no player counts, and no exception either")
    void withoutAServerTheCountsAreOmitted() {
        // The snapshot is taken on heimdall-ws every heartbeat. Throwing there costs the tick its
        // health AND its liveness refresh, so the counts are dropped instead.
        Payload snapshot = new BukkitHealthSource(TickSource.UNAVAILABLE).snapshot();
        assertFalse(snapshot.has("onlinePlayers"));
        assertFalse(snapshot.has("maxPlayers"));
    }

    @Test
    @DisplayName("without a server the MOTD is empty and the icon is missing")
    void withoutAServerStatusFieldsDegrade() {
        Payload snapshot = new BukkitHealthSource(TickSource.UNAVAILABLE).snapshot();
        assertEquals("", snapshot.string("motdClean", "missing"));
        assertFalse(snapshot.has("motdRaw"));
        assertFalse(snapshot.has("iconPngBase64"));
        assertTrue(snapshot.has("usedMemMb"));
        assertFalse(snapshot.has("tps"));
    }

    @Test
    @DisplayName("a stubbed MOTD and favicon appear on the snapshot")
    void stubbedMotdAndFaviconAppear(@TempDir File dir) throws IOException {
        File icon = new File(dir, "server-icon.png");
        byte[] png = "bukkit-icon".getBytes(StandardCharsets.US_ASCII);
        Files.write(icon.toPath(), png);

        Payload snapshot = new BukkitHealthSource(ticks(20.0, 4.21), new BukkitHealthSource.MotdFaviconSource() {
            @Override
            public String motd() {
                return "\u00A7cHello \u00A7aWorld";
            }

            @Override
            public File iconFile() {
                return icon;
            }
        }).snapshot();

        assertEquals("Hello World", snapshot.string("motdClean", ""));
        assertEquals("\u00A7cHello \u00A7aWorld", snapshot.string("motdRaw", ""));
        assertEquals(Base64.getEncoder().encodeToString(png), snapshot.string("iconPngBase64", ""));
        assertFalse(snapshot.string("iconPngBase64", "data:").startsWith("data:"));
        assertEquals(20.0, snapshot.doubleValue("tps", -1.0), 0.0001);
        assertEquals(4.21, snapshot.doubleValue("mspt", -1.0), 0.0001);
        assertTrue(snapshot.has("usedMemMb"));
        assertTrue(snapshot.has("maxMemMb"));
    }

    @Test
    @DisplayName("an unreadable stub MOTD still sends motdClean empty")
    void unreadMotdIsEmptyString() {
        Payload snapshot = new BukkitHealthSource(TickSource.UNAVAILABLE, new BukkitHealthSource.MotdFaviconSource() {
            @Override
            public String motd() {
                throw new IllegalStateException("not ready");
            }

            @Override
            public File iconFile() {
                throw new IllegalStateException("not ready");
            }
        }).snapshot();

        assertEquals("", snapshot.string("motdClean", "missing"));
        assertFalse(snapshot.has("iconPngBase64"));
        assertTrue(snapshot.has("usedMemMb"));
    }

    @Test
    @DisplayName("an oversized stub favicon is dropped")
    void oversizedFaviconIsDropped(@TempDir File dir) throws IOException {
        File icon = new File(dir, "server-icon.png");
        Files.write(icon.toPath(), new byte[StatusHealth.MAX_ICON_BYTES + 1]);

        Payload snapshot = new BukkitHealthSource(TickSource.UNAVAILABLE, new BukkitHealthSource.MotdFaviconSource() {
            @Override
            public String motd() {
                return "ok";
            }

            @Override
            public File iconFile() {
                return icon;
            }
        }).snapshot();

        assertEquals("ok", snapshot.string("motdClean", ""));
        assertFalse(snapshot.has("iconPngBase64"));
    }
}
