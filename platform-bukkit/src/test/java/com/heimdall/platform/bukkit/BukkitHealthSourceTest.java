package com.heimdall.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.json.Payload;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.platform.bukkit.adapter.TickSource;
import com.heimdall.platform.common.StatusHealth;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
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
 *
 * <p>{@code vanishedPlayers} is the one field that breaks the omission rule, and the tests say why:
 * zero hidden players is a measurement, so it is sent, and a snapshot with no such field at all means
 * a proxy or a jar older than the count.
 */
class BukkitHealthSourceTest {

    /** Debug on, so the vanish guard's log line is executed rather than skipped by a level check. */
    private static final RecordingLogger LOGGER = new RecordingLogger(true);

    /** No MOTD and no icon, without a server to fail to read one from. */
    private static final BukkitHealthSource.MotdFaviconSource MISSING_STATUS =
            new BukkitHealthSource.MotdFaviconSource() {
                @Override
                public String motd() {
                    return null;
                }

                @Override
                public File iconFile() {
                    return null;
                }
            };

    /** A source reading a fixed roster. {@code maxPlayers} is still absent: that one needs a server. */
    private static BukkitHealthSource withRoster(final Player... online) {
        return new BukkitHealthSource(LOGGER, TickSource.UNAVAILABLE, MISSING_STATUS,
                new BukkitPlayerDirectory.RosterSource() {
                    @Override
                    public Collection<? extends Player> onlinePlayers() {
                        return Arrays.asList(online);
                    }
                });
    }

    /** A player the shared vanish metadata key is, or is not, set on. */
    private static Player player(boolean vanished) {
        Player player = mock(Player.class);
        MetadataValue value = mock(MetadataValue.class);
        when(value.asBoolean()).thenReturn(vanished);
        when(player.getMetadata("vanished")).thenReturn(Collections.singletonList(value));
        return player;
    }

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
        Payload snapshot = new BukkitHealthSource(LOGGER, TickSource.UNAVAILABLE).snapshot();
        assertTrue(snapshot.has("usedMemMb"));
        assertTrue(snapshot.has("maxMemMb"));
        assertTrue(snapshot.longValue("maxMemMb", 0L) > 0L, "a JVM always has a heap ceiling");
    }

    @Test
    @DisplayName("a server that cannot report a tick rate sends no tps field at all")
    void unavailableTicksAreOmitted() {
        Payload snapshot = new BukkitHealthSource(LOGGER, TickSource.UNAVAILABLE).snapshot();
        assertFalse(snapshot.has("tps"), "an unmeasurable tps must be absent, never zero");
        assertFalse(snapshot.has("mspt"));
    }

    @Test
    @DisplayName("a source with tps but no mspt sends one field, not two")
    void partialTicks() {
        Payload snapshot = new BukkitHealthSource(LOGGER, ticks(19.98, null)).snapshot();
        assertEquals(19.98, snapshot.doubleValue("tps", -1.0), 0.0001);
        assertFalse(snapshot.has("mspt"), "old Paper reports tps and not mspt; say so by omission");
    }

    @Test
    @DisplayName("both fields survive when both can be measured")
    void fullTicks() {
        Payload snapshot = new BukkitHealthSource(LOGGER, ticks(20.0, 4.21)).snapshot();
        assertEquals(20.0, snapshot.doubleValue("tps", -1.0), 0.0001);
        assertEquals(4.21, snapshot.doubleValue("mspt", -1.0), 0.0001);
    }

    @Test
    @DisplayName("no server behind it means no player counts, and no exception either")
    void withoutAServerTheCountsAreOmitted() {
        // The snapshot is taken on heimdall-ws every heartbeat. Throwing there costs the tick its
        // health AND its liveness refresh, so the counts are dropped instead.
        Payload snapshot = new BukkitHealthSource(LOGGER, TickSource.UNAVAILABLE).snapshot();
        assertFalse(snapshot.has("onlinePlayers"));
        assertFalse(snapshot.has("maxPlayers"));
    }

    @Test
    @DisplayName("no server behind it means no vanish count either")
    void withoutAServerTheVanishCountIsOmitted() {
        assertFalse(new BukkitHealthSource(LOGGER, TickSource.UNAVAILABLE).snapshot().has("vanishedPlayers"),
                "a count nobody could take is absent, exactly like onlinePlayers beside it");
    }

    @Test
    @DisplayName("a roster with nobody hidden reports zero rather than omitting the field")
    void nobodyHiddenIsZero() {
        Payload snapshot = withRoster(player(false), player(false)).snapshot();

        assertEquals(2, snapshot.intValue("onlinePlayers", -1));
        assertEquals(0, snapshot.intValue("vanishedPlayers", -1),
                "'nobody is hidden' is something this server measured, so it is sent; an omitted "
                        + "field means a proxy or an older jar, which is a different fact");
    }

    @Test
    @DisplayName("hidden players are counted, and only the hidden ones")
    void hiddenPlayersAreCounted() {
        Payload snapshot = withRoster(player(true), player(false), player(true)).snapshot();

        assertEquals(3, snapshot.intValue("onlinePlayers", -1));
        assertEquals(2, snapshot.intValue("vanishedPlayers", -1));
    }

    @Test
    @DisplayName("a metadata value that throws counts the player as vanished")
    void aThrowingMetadataValueIsCountedAsVanished() {
        // Fail closed. This server declared vanish@1, so the bot reads an uncounted player as one it
        // may publish; guessing "not vanished" leaks a hidden staff member, and guessing "vanished"
        // costs one name off one refresh. Only the first mistake is irreversible.
        MetadataValue value = mock(MetadataValue.class);
        when(value.asBoolean()).thenThrow(new IllegalStateException("not my thread"));
        List<MetadataValue> values = Collections.singletonList(value);
        Player explosive = mock(Player.class);
        when(explosive.getMetadata("vanished")).thenReturn(values);

        Payload snapshot = withRoster(explosive, player(true), player(false)).snapshot();

        assertEquals(2, snapshot.intValue("vanishedPlayers", -1));
        assertEquals(3, snapshot.intValue("onlinePlayers", -1));
        assertTrue(snapshot.has("usedMemMb"), "the rest of the snapshot still goes");
    }

    @Test
    @DisplayName("a roster that keeps racing costs both counts, not just the vanish one")
    void aRosterThatKeepsRacingSendsNeitherCount() {
        // Five consecutive races is no longer a race. The important half is that onlinePlayers goes
        // with it: a bot subtracting vanishedPlayers to get a publishable figure would, handed the
        // total alone, publish a number that still includes hidden staff. A one-tick gap in the
        // chart beats a one-tick leak.
        Payload snapshot = new BukkitHealthSource(LOGGER, TickSource.UNAVAILABLE, MISSING_STATUS,
                new BukkitPlayerDirectory.RosterSource() {
                    @Override
                    public Collection<? extends Player> onlinePlayers() {
                        return new AbstractList<Player>() {
                            @Override
                            public Player get(int index) {
                                throw new ConcurrentModificationException("somebody joined");
                            }

                            @Override
                            public int size() {
                                return 2;
                            }
                        };
                    }
                }).snapshot();

        assertFalse(snapshot.has("onlinePlayers"),
                "the total on its own is the one degradation worse than sending nothing");
        assertFalse(snapshot.has("vanishedPlayers"));
        assertTrue(snapshot.has("usedMemMb"));
    }

    @Test
    @DisplayName("a single race is retried away and both counts still go")
    void oneRaceIsRetriedAway() {
        // A race needs a join or a quit inside the microseconds a copy takes, so the second attempt
        // essentially always wins. Giving up on the first would drop the counts off a healthy server
        // every time somebody logged in.
        final int[] attempts = {0};
        Payload snapshot = new BukkitHealthSource(LOGGER, TickSource.UNAVAILABLE, MISSING_STATUS,
                new BukkitPlayerDirectory.RosterSource() {
                    @Override
                    public Collection<? extends Player> onlinePlayers() {
                        if (attempts[0]++ == 0) {
                            throw new ConcurrentModificationException("somebody joined");
                        }
                        return Arrays.asList(player(true), player(false));
                    }
                }).snapshot();

        assertEquals(2, attempts[0], "the second attempt is the one that answered");
        assertEquals(2, snapshot.intValue("onlinePlayers", -1));
        assertEquals(1, snapshot.intValue("vanishedPlayers", -1));
    }

    @Test
    @DisplayName("without a server the MOTD is empty and the icon is missing")
    void withoutAServerStatusFieldsDegrade() {
        Payload snapshot = new BukkitHealthSource(LOGGER, TickSource.UNAVAILABLE).snapshot();
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

        Payload snapshot = new BukkitHealthSource(LOGGER, ticks(20.0, 4.21), new BukkitHealthSource.MotdFaviconSource() {
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
        Payload snapshot = new BukkitHealthSource(LOGGER, TickSource.UNAVAILABLE, new BukkitHealthSource.MotdFaviconSource() {
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

        Payload snapshot = new BukkitHealthSource(LOGGER, TickSource.UNAVAILABLE, new BukkitHealthSource.MotdFaviconSource() {
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
