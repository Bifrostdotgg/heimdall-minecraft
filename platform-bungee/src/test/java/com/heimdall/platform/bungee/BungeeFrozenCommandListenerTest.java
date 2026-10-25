package com.heimdall.platform.bungee;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.log.RecordingLogger;
import java.util.UUID;
import java.util.function.Predicate;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.ChatEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A frozen player's proxy commands are cancelled; backend commands and chat are untouched. */
class BungeeFrozenCommandListenerTest {

    private final RecordingLogger logger = new RecordingLogger(true);

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
}
