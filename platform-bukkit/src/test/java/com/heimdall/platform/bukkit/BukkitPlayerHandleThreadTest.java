package com.heimdall.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.platform.SchedulerBridge;
import com.heimdall.core.util.Registration;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kicks and chat hop onto the player's own thread, not "the" main thread.
 *
 * <p>On Paper those are the same thread and the distinction is invisible. On Folia a hop to the
 * global region and then {@code kickPlayer} is the wrong thread the moment the player is anywhere
 * else — which is why {@link BukkitPlayerHandle} has to go through
 * {@link SchedulerBridge#runOnEntityThread} rather than {@code mainThread.execute}.
 */
class BukkitPlayerHandleThreadTest {

    @Test
    @DisplayName("kick is scheduled on the entity thread of this handle")
    void kickHopsToTheEntityThread() {
        RecordingScheduler scheduler = new RecordingScheduler();
        Player steve = player("Steve");
        BukkitPlayerHandle handle = new BukkitPlayerHandle(steve, scheduler, null);

        handle.kick(Component.text("not whitelisted"));

        assertSame(handle, scheduler.lastPlayer);
        assertEquals(1, scheduler.entityHops);
        assertEquals(0, scheduler.laterCalls);
    }

    @Test
    @DisplayName("sendMessage is scheduled on the entity thread of this handle")
    void sendMessageHopsToTheEntityThread() {
        RecordingScheduler scheduler = new RecordingScheduler();
        Player steve = player("Steve");
        BukkitPlayerHandle handle = new BukkitPlayerHandle(steve, scheduler, null);

        handle.sendMessage(Component.text("hello"));

        assertSame(handle, scheduler.lastPlayer);
        assertEquals(1, scheduler.entityHops);
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private static final class RecordingScheduler implements SchedulerBridge {

        PlayerHandle lastPlayer;
        int entityHops;
        int laterCalls;

        @Override
        public void runOnEntityThread(PlayerHandle player, Runnable task) {
            entityHops++;
            lastPlayer = player;
        }

        @Override
        public Registration runLater(Runnable task, long delayMs) {
            laterCalls++;
            return Registration.NONE;
        }
    }
}
