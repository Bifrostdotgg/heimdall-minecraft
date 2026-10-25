package com.heimdall.platform.bungee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.log.RecordingLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.md_5.bungee.api.Callback;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.ServerKickEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A frozen player's proxy commands are cancelled; backend commands and chat are untouched. */
class BungeeFrozenCommandListenerTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final List<UUID> kicks = new ArrayList<UUID>();
    private final List<Consumer<Consumer<Boolean>>> pings =
            new ArrayList<Consumer<Consumer<Boolean>>>();

    private static ProxiedPlayer steve() {
        ProxiedPlayer steve = mock(ProxiedPlayer.class);
        when(steve.getUniqueId()).thenReturn(UUID.randomUUID());
        return steve;
    }

    private static ChatEvent event(Connection sender, String message, final boolean proxyCommand) {
        return new ChatEvent(sender, mock(Server.class), message) {
            @Override
            public boolean isProxyCommand() {
                return proxyCommand;
            }
        };
    }

    private BungeeFrozenCommandListener listener(final boolean frozen) {
        return new BungeeFrozenCommandListener(logger, new Predicate<UUID>() {
            @Override
            public boolean test(UUID player) {
                return frozen;
            }
        }, new BiConsumer<UUID, Consumer<Consumer<Boolean>>>() {
            @Override
            public void accept(UUID player, Consumer<Consumer<Boolean>> ping) {
                kicks.add(player);
                pings.add(ping);
            }
        });
    }

    @Test
    @DisplayName("a frozen player's /server is cancelled")
    void serverCancelled() {
        ChatEvent event = event(steve(), "/server survival", true);
        listener(true).onChat(event);
        assertTrue(event.isCancelled());
    }

    @Test
    @DisplayName("a player who is not frozen keeps their proxy commands")
    void notFrozen() {
        ChatEvent event = event(steve(), "/server survival", true);
        listener(false).onChat(event);
        assertFalse(event.isCancelled());
    }

    @Test
    @DisplayName("backend commands and chat are left to the backend")
    void backendUntouched() {
        ChatEvent command = event(steve(), "/msg Alex hi", false);
        ChatEvent chat = event(steve(), "hello", false);
        listener(true).onChat(command);
        listener(true).onChat(chat);
        assertFalse(command.isCancelled());
        assertFalse(chat.isCancelled());
    }

    @Test
    @DisplayName("a sender that is not a player is never cancelled")
    void notAPlayer() {
        ChatEvent event = event(mock(Server.class), "/server x", true);
        listener(true).onChat(event);
        assertFalse(event.isCancelled());
    }

    private static ServerKickEvent kick(ProxiedPlayer player, boolean cancelled, ServerInfo cancelTo,
            ServerInfo from) {
        ServerKickEvent event = mock(ServerKickEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.isCancelled()).thenReturn(cancelled);
        when(event.getCancelServer()).thenReturn(cancelTo);
        when(event.getKickedFrom()).thenReturn(from);
        return event;
    }

    @Test
    @DisplayName("a kick that disconnects is noted, with a ping of the server it came from")
    @SuppressWarnings("unchecked")
    void kickNoted() {
        ServerInfo from = mock(ServerInfo.class);
        doAnswer(call -> {
            ((Callback<ServerPing>) call.getArgument(0)).done(null, new java.io.IOException("down"));
            return null;
        }).when(from).ping(any(Callback.class));
        ProxiedPlayer steve = steve();
        listener(true).onKick(kick(steve, false, null, from));
        assertEquals(1, kicks.size());
        assertEquals(steve.getUniqueId(), kicks.get(0));
        final Boolean[] up = new Boolean[1];
        pings.get(0).accept(answer -> up[0] = answer);
        assertEquals(Boolean.FALSE, up[0]);
    }

    @Test
    @DisplayName("a kick that moves the player to another server is not a disconnect")
    void kickToFallbackIgnored() {
        listener(true).onKick(kick(steve(), true, mock(ServerInfo.class), mock(ServerInfo.class)));
        assertTrue(kicks.isEmpty());
    }
}
