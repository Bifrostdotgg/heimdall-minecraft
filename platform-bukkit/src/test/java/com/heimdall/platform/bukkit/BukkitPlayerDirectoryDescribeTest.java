package com.heimdall.platform.bukkit;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.json.Payload;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.PlayerHandle;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The third column of the dashboard's Online Players panel, on a backend server.
 *
 * <p>{@code ip} is v2's Bukkit roster field and the dashboard was written against it, so this is a
 * wire-shape test rather than a formatting one. The static {@code Bukkit.getOnlinePlayers()} half is
 * out of reach without a running server; {@code describe} deliberately takes a handle instead, which
 * is the whole of what makes it testable here.
 *
 * <p>The {@code vanished} flag is the second wire-shape rule pinned here, and the interesting half is
 * the absence: it is written only when true, so "not hidden" and "this build cannot tell" stay the
 * same bytes and the capability is what distinguishes them.
 */
class BukkitPlayerDirectoryDescribeTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final BukkitPlayerDirectory directory =
            new BukkitPlayerDirectory(logger, InlineScheduler.INSTANCE, null);

    private PlayerHandle handleFor(InetSocketAddress address) {
        return directory.wrap(player(address));
    }

    private static Player player(InetSocketAddress address) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Steve");
        when(player.getAddress()).thenReturn(address);
        return player;
    }

    /** A metadata value that answers {@code asBoolean()} with whatever it is given. */
    private static MetadataValue metadata(final boolean value) {
        MetadataValue stored = mock(MetadataValue.class);
        when(stored.asBoolean()).thenReturn(value);
        return stored;
    }

    @Test
    @DisplayName("the connecting address is reported under 'ip'")
    void theAddressIsReported() throws Exception {
        Payload described = directory.describe(
                handleFor(new InetSocketAddress(InetAddress.getByName("203.0.113.7"), 25565)));

        assertEquals("203.0.113.7", described.string("ip", ""));
        assertFalse(described.has("server"),
                "a Bukkit server is not in front of anything, so there is no backend to name — that "
                        + "question belongs to the proxy's directory");
    }

    @Test
    @DisplayName("an address that cannot be read is 'unknown', not a missing key")
    void anUnreadableAddressIsUnknown() {
        // getAddress() answers null for a player on their way out, which happens between the roster
        // snapshot and this read. v2 wrote the literal string "unknown" here and the dashboard's
        // panel was written against it.
        assertEquals("unknown", directory.describe(handleFor(null)).string("ip", ""));
    }

    @Test
    @DisplayName("an ordinary player carries no 'vanished' key at all")
    void anUnhiddenPlayerHasNoVanishedKey() {
        // Omitted rather than false: every bot built before vanish reporting existed reads a row
        // without the key exactly as it always did, and there is nothing new for it to ignore.
        assertFalse(directory.describe(handleFor(null)).has("vanished"));
    }

    @Test
    @DisplayName("a player carrying the shared 'vanished' metadata is flagged true")
    void aVanishedPlayerIsFlagged() {
        // The key EssentialsX, SuperVanish, PremiumVanish, CMI and VanishNoPacket all set. Reading
        // it is how one line supports five vanish plugins without compiling against any of them.
        // The values are built before the stubbing starts: mocking inside an open when(...) is what
        // Mockito calls unfinished stubbing, and it fails the test rather than the assertion.
        List<MetadataValue> hidden = singletonList(metadata(true));
        Player player = player(null);
        when(player.getMetadata("vanished")).thenReturn(hidden);

        Payload described = directory.describe(directory.wrap(player));

        assertTrue(described.bool("vanished", false));
        assertEquals("unknown", described.string("ip", ""),
                "the flag rides alongside the platform's own column rather than replacing it");
    }

    @Test
    @DisplayName("a value that says false leaves the key off, and one plugin saying true wins")
    void falseValuesAreNotAFlag() {
        List<MetadataValue> saysNo = singletonList(metadata(false));
        Player quiet = player(null);
        when(quiet.getMetadata("vanished")).thenReturn(saysNo);
        assertFalse(directory.describe(directory.wrap(quiet)).has("vanished"));

        // Two vanish plugins installed, one of them holding a stale false. "Hidden by something" is
        // the answer that keeps a hidden staff member hidden.
        List<MetadataValue> disagreeing = asList(metadata(false), metadata(true));
        Player hidden = player(null);
        when(hidden.getMetadata("vanished")).thenReturn(disagreeing);
        assertTrue(directory.describe(directory.wrap(hidden)).bool("vanished", false));
    }

    @Test
    @DisplayName("a metadata value that throws is flagged vanished, and the row still answers")
    void aThrowingMetadataValueFailsClosed() {
        // asBoolean() on a lazy value runs a third-party plugin's code on this thread, and this
        // thread is building a row in a reply the bot is waiting on. A throw must cost the flag's
        // accuracy, never the roster - and it fails CLOSED, because this server told the bot it can
        // see who is hidden, so an unflagged row reads as "safe to publish".
        MetadataValue explosive = mock(MetadataValue.class);
        when(explosive.asBoolean()).thenThrow(new IllegalStateException("not my thread"));
        List<MetadataValue> values = singletonList(explosive);
        Player player = player(null);
        when(player.getMetadata("vanished")).thenReturn(values);

        Payload described = directory.describe(directory.wrap(player));

        assertTrue(described.bool("vanished", false),
                "hiding someone who was not hidden costs one name off one refresh; publishing "
                        + "someone who was cannot be taken back");
        assertEquals("unknown", described.string("ip", ""));
        assertTrue(logger.logged(LogLevel.DEBUG, "counting them as vanished"),
                "the guess is recorded, or an operator has no way to see it happening");
    }

    @Test
    @DisplayName("a metadata store that cannot be read at all fails closed too")
    void anUnreadableMetadataStoreFailsClosed() {
        // getMetadata is synchronised and answers an empty list for a key nobody set, so a server
        // with no vanish plugin never reaches this branch: reaching it means something is wrong with
        // this player, which is not the moment to guess in the publishable direction.
        Player player = player(null);
        when(player.getMetadata("vanished")).thenThrow(new IllegalStateException("shutting down"));

        assertTrue(directory.describe(directory.wrap(player)).bool("vanished", false));
    }

    @Test
    @DisplayName("the directory declares that it can see vanish state")
    void theDirectoryReportsVanish() {
        // This, and only this, is what puts vanish@1 on identify. A proxy's directory inherits the
        // default and declares nothing.
        assertTrue(directory.reportsVanish());
    }

    @Test
    @DisplayName("a handle from somewhere else describes to nothing rather than throwing")
    void aForeignHandleIsTolerated() {
        PlayerHandle foreign = mock(PlayerHandle.class);

        assertEquals(Payload.empty(), directory.describe(foreign),
                "the caller is building a frame the bot is waiting on — a reply with one thin row "
                        + "beats an exception that turns into a 504");
    }
}
