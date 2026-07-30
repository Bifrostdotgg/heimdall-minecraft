package com.heimdall.platform.bungee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.json.Payload;
import com.heimdall.core.platform.PlayerHandle;
import java.util.UUID;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The third column of the dashboard's Online Players panel, on this proxy.
 *
 * <p>{@code server} is v2's proxy roster field and is the key the Velocity binding already answers
 * with, so the panel renders a BungeeCord network and a Velocity one identically. The absence of
 * {@code ip} is asserted rather than merely not written: the proxy knows every player's address
 * perfectly well, and the reason it does not publish one is that v2's proxy panel never had that
 * column — not something to start doing on the way past.
 */
class BungeePlayerDirectoryDescribeTest {

    private final BungeeText text = new BungeeText();
    private final BungeePlayerDirectory directory =
            new BungeePlayerDirectory(mock(ProxyServer.class), text);

    private PlayerHandle handleOn(String backendName) {
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Steve");
        if (backendName == null) {
            // Not an empty Optional the way Velocity's is: BungeeCord returns a bare null from
            // getServer() for a player who is on no backend, which is why the guard here is a null
            // check and not a map/orElse.
            when(player.getServer()).thenReturn(null);
        } else {
            ServerInfo info = mock(ServerInfo.class);
            when(info.getName()).thenReturn(backendName);
            Server connected = mock(Server.class);
            when(connected.getInfo()).thenReturn(info);
            when(player.getServer()).thenReturn(connected);
        }
        return new BungeePlayerHandle(player, text);
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
        // connect, and everyone parked in a queue plugin. v2 wrote the literal string "unknown", and
        // the Velocity binding answers the same word for the same state.
        assertEquals("unknown", directory.describe(handleOn(null)).string("server", ""));
    }

    @Test
    @DisplayName("a backend whose info has gone answers 'unknown' rather than failing the roster")
    void aTornDownConnectionIsUnknown() {
        // The row is one of many in a reply the bot is waiting on. A connection torn down between
        // the roster snapshot and this read costs that row its column, not the whole answer.
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        Server connected = mock(Server.class);
        when(connected.getInfo()).thenThrow(new IllegalStateException("connection closed"));
        when(player.getServer()).thenReturn(connected);

        assertEquals("unknown",
                directory.describe(new BungeePlayerHandle(player, text)).string("server", ""));
    }

    @Test
    @DisplayName("a handle from somewhere else describes to nothing rather than throwing")
    void aForeignHandleIsTolerated() {
        assertEquals(Payload.empty(), directory.describe(mock(PlayerHandle.class)));
    }
}
