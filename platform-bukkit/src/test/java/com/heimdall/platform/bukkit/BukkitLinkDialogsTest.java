package com.heimdall.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.link.LinkCodePrompt;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.platform.SchedulerBridge;
import com.heimdall.core.util.Registration;
import com.heimdall.platform.bukkit.adapter.LinkDialogPresenter;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Bukkit half of the link dialog: the hop to the player's thread, and the rule that every
 * prompt it accepts reaches the player once, as a dialog or as chat.
 *
 * <p>The dialog itself is {@code :platform-bukkit-paper}'s and is tested there against the real
 * Paper API. Here it is a presenter that does what each test tells it to.
 */
class BukkitLinkDialogsTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final LinkCodePrompt prompt = LinkCodePrompt.of("123456", null);
    private final AtomicInteger chat = new AtomicInteger();
    private final Runnable fallback = chat::incrementAndGet;

    /** A presenter with a scripted answer, counting what it was asked. */
    private static final class ScriptedPresenter implements LinkDialogPresenter {

        volatile boolean client = true;
        volatile boolean sends = true;
        volatile RuntimeException failure;
        final AtomicInteger shown = new AtomicInteger();

        @Override
        public boolean canShow(Player player) {
            return client;
        }

        @Override
        public boolean show(Player player, LinkCodePrompt prompt, long nowMs) {
            if (failure != null) {
                throw failure;
            }
            if (sends) {
                shown.incrementAndGet();
            }
            return sends;
        }

        @Override
        public String describe() {
            return "scripted";
        }
    }

    private BukkitLinkDialogs dialogs(ScriptedPresenter presenter, SchedulerBridge scheduler) {
        return new BukkitLinkDialogs(logger, scheduler, presenter, () -> 0L);
    }

    private static BukkitPlayerHandle handle(boolean online) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes("Steve".getBytes()));
        when(player.getName()).thenReturn("Steve");
        when(player.isOnline()).thenReturn(online);
        return new BukkitPlayerHandle(player, InlineScheduler.INSTANCE, null);
    }

    @Test
    @DisplayName("a client that can draw it gets the dialog, and no chat")
    void dialog() {
        ScriptedPresenter presenter = new ScriptedPresenter();

        assertTrue(dialogs(presenter, InlineScheduler.INSTANCE).show(handle(true), prompt, fallback));

        assertEquals(1, presenter.shown.get());
        assertEquals(0, chat.get());
    }

    @Test
    @DisplayName("an older client, a send that fails, or one that throws, all become chat, once")
    void everyFailureIsChat() {
        ScriptedPresenter presenter = new ScriptedPresenter();
        BukkitLinkDialogs dialogs = dialogs(presenter, InlineScheduler.INSTANCE);

        presenter.client = false;
        assertTrue(dialogs.show(handle(true), prompt, fallback));
        assertEquals(1, chat.get(), "a 1.21.5 client on a 1.21.7 server");

        presenter.client = true;
        presenter.sends = false;
        assertTrue(dialogs.show(handle(true), prompt, fallback));
        assertEquals(2, chat.get());

        presenter.failure = new IllegalStateException("a renamed method on a newer Paper");
        assertTrue(dialogs.show(handle(true), prompt, fallback));
        assertEquals(3, chat.get());
        assertEquals(0, presenter.shown.get());
    }

    @Test
    @DisplayName("nothing about the player is asked until the hop has happened")
    void decidedOnThePlayersThread() {
        ScriptedPresenter presenter = new ScriptedPresenter();
        final Runnable[] held = new Runnable[1];
        SchedulerBridge later = new SchedulerBridge() {
            @Override
            public void runOnEntityThread(PlayerHandle player, Runnable task) {
                held[0] = task;
            }

            @Override
            public Registration runLater(Runnable task, long delayMs) {
                return Registration.NONE;
            }
        };

        assertTrue(dialogs(presenter, later).show(handle(true), prompt, fallback));
        assertEquals(0, presenter.shown.get(), "heimdall-io must not touch the Bukkit player");

        held[0].run();
        assertEquals(1, presenter.shown.get());
    }

    @Test
    @DisplayName("a scheduler that refuses hands the prompt back to core for chat")
    void refusedHopIsFalse() {
        SchedulerBridge refusing = new SchedulerBridge() {
            @Override
            public void runOnEntityThread(PlayerHandle player, Runnable task) {
                throw new RejectedExecutionException("plugin disabling");
            }

            @Override
            public Registration runLater(Runnable task, long delayMs) {
                return Registration.NONE;
            }
        };

        assertFalse(dialogs(new ScriptedPresenter(), refusing).show(handle(true), prompt, fallback));
        assertEquals(0, chat.get(), "false means core sends chat itself; running it too is twice");
    }

    @Test
    @DisplayName("a player who left before the hop is shown nothing by either route")
    void goneBeforeTheHop() {
        ScriptedPresenter presenter = new ScriptedPresenter();

        assertTrue(dialogs(presenter, InlineScheduler.INSTANCE).show(handle(false), prompt, fallback));

        assertEquals(0, presenter.shown.get());
        // The fallback runs, and is core's chat, which BukkitPlayerHandle drops for an offline
        // player. Counted here because this test's fallback is a counter, not chat.
        assertEquals(1, chat.get());
    }

    @Test
    @DisplayName("a handle that is not a Bukkit player is chat")
    void foreignHandle() {
        PlayerHandle foreign = new PlayerHandle() {
            @Override
            public UUID uuid() {
                return UUID.randomUUID();
            }

            @Override
            public String name() {
                return "Elsewhere";
            }

            @Override
            public void kick(Component reason) {
            }

            @Override
            public void sendMessage(Component message) {
            }

            @Override
            public boolean hasPermission(String node) {
                return false;
            }
        };

        assertFalse(dialogs(new ScriptedPresenter(), InlineScheduler.INSTANCE)
                .show(foreign, prompt, fallback));
    }
}
