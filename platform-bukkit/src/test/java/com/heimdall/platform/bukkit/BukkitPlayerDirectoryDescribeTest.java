package com.heimdall.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.json.Payload;
import com.heimdall.core.platform.PlayerHandle;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The third column of the dashboard's Online Players panel, on a backend server.
 *
 * <p>{@code ip} is v2's Bukkit roster field and the dashboard was written against it, so this is a
 * wire-shape test rather than a formatting one. The static {@code Bukkit.getOnlinePlayers()} half is
 * out of reach without a running server; {@code describe} deliberately takes a handle instead, which
 * is the whole of what makes it testable here.
 */
class BukkitPlayerDirectoryDescribeTest {

    private static final Executor INLINE = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private final BukkitPlayerDirectory directory = new BukkitPlayerDirectory(INLINE, null);

    private PlayerHandle handleFor(InetSocketAddress address) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Steve");
        when(player.getAddress()).thenReturn(address);
        return directory.wrap(player);
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
    @DisplayName("a handle from somewhere else describes to nothing rather than throwing")
    void aForeignHandleIsTolerated() {
        PlayerHandle foreign = mock(PlayerHandle.class);

        assertEquals(Payload.empty(), directory.describe(foreign),
                "the caller is building a frame the bot is waiting on — a reply with one thin row "
                        + "beats an exception that turns into a 504");
    }
}
