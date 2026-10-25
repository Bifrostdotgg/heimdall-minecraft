package com.heimdall.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.log.RecordingLogger;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A frozen player's proxy commands are refused; nothing bound for a backend is touched. */
class VelocityFrozenCommandListenerTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final UUID steveId = UUID.randomUUID();
    private final List<UUID> asked = new ArrayList<UUID>();

    private VelocityFrozenCommandListener listener(final boolean frozen) {
        CommandManager commands = mock(CommandManager.class);
        when(commands.hasCommand("server")).thenReturn(true);
        when(commands.hasCommand("hub")).thenReturn(true);
        return new VelocityFrozenCommandListener(logger, commands, new Predicate<UUID>() {
            @Override
            public boolean test(UUID player) {
                asked.add(player);
                return frozen;
            }
        });
    }

    private Player steve() {
        Player steve = mock(Player.class);
        when(steve.getUniqueId()).thenReturn(steveId);
        return steve;
    }

    @Test
    @DisplayName("a frozen player's /server is refused")
    void serverRefused() {
        CommandExecuteEvent event = new CommandExecuteEvent(steve(), "server survival");
        listener(true).onCommand(event);
        assertFalse(event.getResult().isAllowed());
        assertEquals(steveId, asked.get(0));
    }

    @Test
    @DisplayName("a player who is not frozen keeps their proxy commands")
    void notFrozenAllowed() {
        CommandExecuteEvent event = new CommandExecuteEvent(steve(), "hub");
        listener(false).onCommand(event);
        assertTrue(event.getResult().isAllowed());
    }

    @Test
    @DisplayName("a command bound for a backend is left to the backend, without asking")
    void backendCommandUntouched() {
        CommandExecuteEvent event = new CommandExecuteEvent(steve(), "msg Alex hi");
        listener(true).onCommand(event);
        assertTrue(event.getResult().isAllowed(),
                "denying a signed backend command disconnects the player");
        assertTrue(asked.isEmpty());
    }

    @Test
    @DisplayName("the console is never refused")
    void consoleUntouched() {
        CommandExecuteEvent event = new CommandExecuteEvent(mock(CommandSource.class), "server x");
        listener(true).onCommand(event);
        assertTrue(event.getResult().isAllowed());
    }

    @Test
    @DisplayName("the alias is the first word, without a slash")
    void alias() {
        assertEquals("server", VelocityFrozenCommandListener.alias("/server lobby"));
        assertEquals("hub", VelocityFrozenCommandListener.alias("hub"));
        assertEquals("", VelocityFrozenCommandListener.alias("  "));
        assertEquals("", VelocityFrozenCommandListener.alias(null));
    }
}
