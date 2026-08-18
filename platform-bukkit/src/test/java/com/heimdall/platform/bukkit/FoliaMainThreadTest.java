package com.heimdall.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.util.Registration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which region a hop lands on, without a Folia server.
 *
 * <p>The production binding is reflection against {@code getGlobalRegionScheduler} /
 * {@code Entity#getScheduler}. The branch under test is which of those two is asked, and whether
 * a caller already on the destination thread runs inline — otherwise this is only reachable by
 * starting Minecraft.
 */
class FoliaMainThreadTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final RecordingTasks tasks = new RecordingTasks();
    private final FoliaMainThread thread = new FoliaMainThread(logger, tasks);

    @Test
    @DisplayName("execute on the global region runs inline, not through the scheduler")
    void executeOnTheGlobalRegionIsInline() {
        tasks.globalThread = true;
        AtomicInteger ran = new AtomicInteger();

        thread.execute(new Runnable() {
            @Override
            public void run() {
                ran.incrementAndGet();
            }
        });

        assertEquals(1, ran.get());
        assertEquals(0, tasks.globalRuns);
    }

    @Test
    @DisplayName("execute off the global region hops to it")
    void executeOffTheGlobalRegionHops() {
        tasks.globalThread = false;
        AtomicInteger ran = new AtomicInteger();

        thread.execute(new Runnable() {
            @Override
            public void run() {
                ran.incrementAndGet();
            }
        });

        assertEquals(1, tasks.globalRuns);
        assertEquals(1, ran.get());
    }

    @Test
    @DisplayName("a kick for a live player hops to that player's entity scheduler")
    void aLivePlayerHopsToTheEntityScheduler() {
        Player steve = player("Steve");
        BukkitPlayerHandle handle = new BukkitPlayerHandle(steve, thread, null);
        AtomicInteger ran = new AtomicInteger();

        thread.runOnEntityThread(handle, new Runnable() {
            @Override
            public void run() {
                ran.incrementAndGet();
            }
        });

        assertEquals(1, tasks.entityRuns);
        assertSame(steve, tasks.lastEntity);
        assertEquals(1, ran.get());
        assertEquals(0, tasks.globalRuns);
    }

    @Test
    @DisplayName("already owning the player's region runs inline")
    void alreadyOwningThePlayerRunsInline() {
        tasks.owns = true;
        Player steve = player("Steve");
        BukkitPlayerHandle handle = new BukkitPlayerHandle(steve, thread, null);
        AtomicInteger ran = new AtomicInteger();

        thread.runOnEntityThread(handle, new Runnable() {
            @Override
            public void run() {
                ran.incrementAndGet();
            }
        });

        assertEquals(0, tasks.entityRuns);
        assertEquals(1, ran.get());
    }

    @Test
    @DisplayName("a handle that is not a Bukkit player falls back to the global region")
    void aForeignHandleFallsBackToGlobal() {
        FakePlayer steve = FakePlayer.named("Steve");
        AtomicInteger ran = new AtomicInteger();

        thread.runOnEntityThread(steve, new Runnable() {
            @Override
            public void run() {
                ran.incrementAndGet();
            }
        });

        assertEquals(0, tasks.entityRuns);
        assertEquals(1, tasks.globalRuns);
        assertEquals(1, ran.get());
    }

    @Test
    @DisplayName("a disconnected player falls back to the global region rather than dropping the task")
    void aDisconnectedPlayerFallsBackToGlobal() {
        Player steve = player("Steve");
        when(steve.isOnline()).thenReturn(false);
        BukkitPlayerHandle handle = new BukkitPlayerHandle(steve, thread, null);
        AtomicInteger ran = new AtomicInteger();

        thread.runOnEntityThread(handle, new Runnable() {
            @Override
            public void run() {
                ran.incrementAndGet();
            }
        });

        assertEquals(0, tasks.entityRuns);
        assertEquals(1, tasks.globalRuns);
        assertEquals(1, ran.get());
    }

    @Test
    @DisplayName("runLater is measured in milliseconds and handed to the global region")
    void runLaterGoesToTheGlobalRegion() {
        thread.runLater(new Runnable() {
            @Override
            public void run() {
            }
        }, 2000L);

        assertEquals(2000L, tasks.lastDelayMs);
        assertTrue(tasks.lastLater != null);
    }

    @Test
    @DisplayName("a refused global hop still runs the task inline")
    void aRefusedGlobalHopRunsInline() {
        tasks.failGlobal = true;
        AtomicInteger ran = new AtomicInteger();

        thread.execute(new Runnable() {
            @Override
            public void run() {
                ran.incrementAndGet();
            }
        });

        assertEquals(1, ran.get());
    }

    @Test
    @DisplayName("tryCreate without region schedulers is null, not an exception")
    void tryCreateWithoutTheApiIsNull() {
        // Unit tests have no Bukkit server, so FoliaSupport.hasRegionSchedulers is false.
        assertEquals(null, FoliaMainThread.tryCreate(null, logger));
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private static final class RecordingTasks implements FoliaMainThread.RegionisedTasks {

        boolean globalThread;
        boolean owns;
        boolean failGlobal;
        int globalRuns;
        int entityRuns;
        long lastDelayMs = -1L;
        Runnable lastLater;
        Player lastEntity;

        @Override
        public boolean isGlobalThread() {
            return globalThread;
        }

        @Override
        public boolean currentlyOwns(Player player) {
            return owns;
        }

        @Override
        public void runGlobal(Runnable task) {
            if (failGlobal) {
                throw new IllegalStateException("plugin is disabling");
            }
            globalRuns++;
            if (task != null) {
                task.run();
            }
        }

        @Override
        public Registration runGlobalLater(Runnable task, long delayMs) {
            lastDelayMs = delayMs;
            lastLater = task;
            return Registration.NONE;
        }

        @Override
        public void runOnEntity(Player player, Runnable task, Runnable retired) {
            entityRuns++;
            lastEntity = player;
            if (task != null) {
                task.run();
            }
        }
    }
}
