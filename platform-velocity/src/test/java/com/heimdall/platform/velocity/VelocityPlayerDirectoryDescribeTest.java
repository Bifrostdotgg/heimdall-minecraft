package com.heimdall.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.json.Payload;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The third column of the dashboard's Online Players panel, on a proxy.
 *
 * <p>{@code server} is v2's Velocity roster field. The absence of {@code ip} is asserted rather than
 * merely not written: the proxy knows every player's address perfectly well, and the reason it does
 * not publish one is that v2's proxy panel never had that column — not something to start doing on
 * the way past.
 */
class VelocityPlayerDirectoryDescribeTest {

    private final VelocityText text = new VelocityText(new RecordingLogger(true));
    private final VelocityPlayerDirectory directory =
            new VelocityPlayerDirectory(mock(ProxyServer.class), text);

    private PlayerHandle handleOn(String backendName) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getUsername()).thenReturn("Steve");
        if (backendName == null) {
            when(player.getCurrentServer()).thenReturn(Optional.empty());
        } else {
            ServerConnection connection = mock(ServerConnection.class);
            when(connection.getServerInfo())
                    .thenReturn(new ServerInfo(backendName, new InetSocketAddress("127.0.0.1", 25566)));
            when(player.getCurrentServer()).thenReturn(Optional.of(connection));
        }
        return new VelocityPlayerHandle(player, text);
    }

    @Test
    @DisplayName("the backend the player is on is reported under 'server'")
    void theBackendIsReported() {
        Payload described = directory.describe(handleOn("survival"));

        assertEquals("survival", described.string("server", ""));
        assertFalse(described.has("ip"),
                "v2's proxy roster carried no address column, and quietly starting to publish every "
                        + "proxied player's IP is not a change a roster reply gets to make");
    }

    @Test
    @DisplayName("a player not on any backend yet is 'unknown', not a missing key")
    void aPlayerBetweenServersIsUnknown() {
        // Common rather than exotic: everyone between the login handshake and their first server
        // connect, and everyone parked in a queue plugin. v2 wrote the literal string "unknown".
        assertEquals("unknown", directory.describe(handleOn(null)).string("server", ""));
    }

    @Test
    @DisplayName("a handle from somewhere else describes to nothing rather than throwing")
    void aForeignHandleIsTolerated() {
        assertEquals(Payload.empty(), directory.describe(mock(PlayerHandle.class)));
    }
}
