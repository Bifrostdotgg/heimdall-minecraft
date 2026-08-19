package com.heimdall.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.json.Payload;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.util.Favicon;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Player counts, memory, MOTD and favicon on a Velocity health snapshot.
 */
class VelocityHealthSourceTest {

    @Test
    @DisplayName("memory is always reported")
    void memoryIsAlwaysThere() {
        Payload snapshot = new VelocityHealthSource(mock(ProxyServer.class)).snapshot();
        assertTrue(snapshot.has("usedMemMb"));
        assertTrue(snapshot.has("maxMemMb"));
        assertTrue(snapshot.longValue("maxMemMb", 0L) > 0L);
    }

    @Test
    @DisplayName("a proxy never sends tps or mspt")
    void noTickFields() {
        Payload snapshot = new VelocityHealthSource(mock(ProxyServer.class)).snapshot();
        assertFalse(snapshot.has("tps"));
        assertFalse(snapshot.has("mspt"));
    }

    @Test
    @DisplayName("without a stubbed MOTD the clean field is empty and the icon is missing")
    void unreadStatusFieldsDegrade() {
        Payload snapshot = new VelocityHealthSource(mock(ProxyServer.class)).snapshot();
        assertEquals("", snapshot.string("motdClean", "missing"));
        assertFalse(snapshot.has("motdRaw"));
        assertFalse(snapshot.has("iconPngBase64"));
    }

    @Test
    @DisplayName("a stubbed Component MOTD and favicon appear on the snapshot")
    void stubbedMotdAndFaviconAppear() {
        byte[] png = "velocity-icon".getBytes(StandardCharsets.US_ASCII);
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);

        ProxyConfig config = mock(ProxyConfig.class);
        when(config.getShowMaxPlayers()).thenReturn(80);
        when(config.getMotd()).thenReturn(Component.text("Hello").color(NamedTextColor.RED));
        when(config.getFavicon()).thenReturn(Optional.of(new Favicon(dataUri)));

        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getPlayerCount()).thenReturn(3);
        when(proxy.getConfiguration()).thenReturn(config);

        Payload snapshot = new VelocityHealthSource(proxy).snapshot();

        assertEquals(3, snapshot.intValue("onlinePlayers", -1));
        assertEquals(80, snapshot.intValue("maxPlayers", -1));
        assertEquals("Hello", snapshot.string("motdClean", ""));
        assertEquals("\u00A7cHello", snapshot.string("motdRaw", ""));
        assertEquals(Base64.getEncoder().encodeToString(png), snapshot.string("iconPngBase64", ""));
        assertFalse(snapshot.string("iconPngBase64", "data:").startsWith("data:"));
        assertTrue(snapshot.has("usedMemMb"));
        assertFalse(snapshot.has("tps"));
    }

    @Test
    @DisplayName("a throwing configuration leaves motdClean empty")
    void throwingConfigIsEmptyMotd() {
        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getPlayerCount()).thenReturn(1);
        when(proxy.getConfiguration()).thenThrow(new IllegalStateException("starting"));

        Payload snapshot = new VelocityHealthSource(proxy).snapshot();
        assertEquals("", snapshot.string("motdClean", "missing"));
        assertFalse(snapshot.has("iconPngBase64"));
        assertTrue(snapshot.has("usedMemMb"));
    }
}
