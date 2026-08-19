package com.heimdall.platform.bungee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.json.Payload;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import net.md_5.bungee.api.Favicon;
import net.md_5.bungee.api.ProxyConfig;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ListenerInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Player counts, memory, MOTD and favicon on a BungeeCord health snapshot.
 */
class BungeeHealthSourceTest {

    @Test
    @DisplayName("memory is always reported")
    void memoryIsAlwaysThere() {
        Payload snapshot = new BungeeHealthSource(mock(ProxyServer.class)).snapshot();
        assertTrue(snapshot.has("usedMemMb"));
        assertTrue(snapshot.has("maxMemMb"));
        assertTrue(snapshot.longValue("maxMemMb", 0L) > 0L);
    }

    @Test
    @DisplayName("a proxy never sends tps or mspt")
    void noTickFields() {
        Payload snapshot = new BungeeHealthSource(mock(ProxyServer.class)).snapshot();
        assertFalse(snapshot.has("tps"));
        assertFalse(snapshot.has("mspt"));
    }

    @Test
    @DisplayName("without a stubbed MOTD the clean field is empty and the icon is missing")
    void unreadStatusFieldsDegrade() {
        Payload snapshot = new BungeeHealthSource(mock(ProxyServer.class)).snapshot();
        assertEquals("", snapshot.string("motdClean", "missing"));
        assertFalse(snapshot.has("motdRaw"));
        assertFalse(snapshot.has("iconPngBase64"));
    }

    @Test
    @DisplayName("a stubbed listener MOTD and favicon appear on the snapshot")
    void stubbedMotdAndFaviconAppear() {
        byte[] png = "bungee-icon".getBytes(StandardCharsets.US_ASCII);
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);

        ListenerInfo listener = mock(ListenerInfo.class);
        when(listener.getMotd()).thenReturn("\u00A7aHello \u00A7bWorld");

        ProxyConfig config = mock(ProxyConfig.class);
        when(config.getPlayerLimit()).thenReturn(100);
        when(config.getListeners()).thenReturn(Collections.singletonList(listener));
        when(config.getFaviconObject()).thenReturn(Favicon.create(dataUri));
        when(config.getFavicon()).thenReturn(dataUri);

        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getOnlineCount()).thenReturn(4);
        when(proxy.getConfig()).thenReturn(config);

        Payload snapshot = new BungeeHealthSource(proxy).snapshot();

        assertEquals(4, snapshot.intValue("onlinePlayers", -1));
        assertEquals(100, snapshot.intValue("maxPlayers", -1));
        assertEquals("Hello World", snapshot.string("motdClean", ""));
        assertEquals("\u00A7aHello \u00A7bWorld", snapshot.string("motdRaw", ""));
        assertEquals(Base64.getEncoder().encodeToString(png), snapshot.string("iconPngBase64", ""));
        assertFalse(snapshot.string("iconPngBase64", "data:").startsWith("data:"));
        assertTrue(snapshot.has("usedMemMb"));
        assertFalse(snapshot.has("tps"));
    }

    @Test
    @DisplayName("an empty listener list still sends motdClean empty")
    void noListenersIsEmptyMotd() {
        ProxyConfig config = mock(ProxyConfig.class);
        when(config.getPlayerLimit()).thenReturn(20);
        when(config.getListeners()).thenReturn(Collections.<ListenerInfo>emptyList());

        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getOnlineCount()).thenReturn(0);
        when(proxy.getConfig()).thenReturn(config);

        Payload snapshot = new BungeeHealthSource(proxy).snapshot();
        assertEquals("", snapshot.string("motdClean", "missing"));
        assertFalse(snapshot.has("iconPngBase64"));
        assertEquals(0, snapshot.intValue("onlinePlayers", -1));
        assertTrue(snapshot.has("usedMemMb"));
    }
}
