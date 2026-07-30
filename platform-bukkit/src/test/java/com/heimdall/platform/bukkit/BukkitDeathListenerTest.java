package com.heimdall.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.session.PlayerDeathListener;
import com.heimdall.core.session.PlayerSessionEvents;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What reaches core when somebody dies, and — the part worth a test — <em>which</em> message.
 *
 * <p>The event is constructed for real rather than mocked: {@code PlayerDeathEvent} carries a
 * mutable death message, and the whole reason the listener runs at {@code MONITOR} is that other
 * plugins rewrite it. A mock returning a fixed string would agree with the test's own idea of the
 * event and prove nothing about that.
 */
class BukkitDeathListenerTest {

    private static final Executor INLINE = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private final RecordingLogger logger = new RecordingLogger(true);
    private final PlayerSessionEvents sessions = new PlayerSessionEvents(logger, INLINE);
    private final List<String> deaths = new ArrayList<String>();

    /** One reported death, flattened so the assertions read as sentences. */
    private BukkitDeathListener listening() {
        sessions.onDeath(new PlayerDeathListener() {
            @Override
            public void onPlayerDeath(PlayerHandle player, String deathMessage, long timestampMs) {
                deaths.add(player.name() + "|" + deathMessage);
            }
        });
        return new BukkitDeathListener(
                logger, sessions, new BukkitPlayerDirectory(INLINE, null));
    }

    private static PlayerDeathEvent death(String message) {
        Player steve = mock(Player.class);
        when(steve.getUniqueId()).thenReturn(UUID.randomUUID());
        when(steve.getName()).thenReturn("Steve");
        return new PlayerDeathEvent(
                steve, Collections.<ItemStack>emptyList(), 0, message);
    }

    @Test
    @DisplayName("the death message reaches core verbatim")
    void deathMessageIsReported() {
        listening().onDeath(death("Steve fell from a high place"));

        assertEquals(
                Collections.singletonList("Steve|Steve fell from a high place"), deaths);
    }

    @Test
    @DisplayName("a message another plugin rewrote is the one relayed, not the vanilla one")
    void aRewrittenMessageWins() {
        // THE assertion for the MONITOR priority. Death-message plugins call setDeathMessage at a
        // priority below MONITOR, so a listener that read the event any earlier would relay the
        // sentence the server had already decided not to show.
        PlayerDeathEvent event = death("Steve fell from a high place");
        event.setDeathMessage("<Steve> took the scenic route down");

        listening().onDeath(event);

        assertEquals(
                Collections.singletonList("Steve|<Steve> took the scenic route down"), deaths,
                "reading before MONITOR would relay the vanilla message on a server that had "
                        + "deliberately replaced it");
    }

    @Test
    @DisplayName("a suppressed death message arrives as null, not as an invented sentence")
    void aSuppressedMessageStaysAbsent() {
        // What `/gamerule showDeathMessages false` and every "silent deaths" plugin produce.
        listening().onDeath(death(null));

        assertEquals(Collections.singletonList("Steve|null"), deaths);
    }

    @Test
    @DisplayName("the event is never modified — this listener only watches")
    void theEventIsUntouched() {
        PlayerDeathEvent event = death("Steve drowned");
        int expBefore = event.getDroppedExp();

        listening().onDeath(event);

        assertEquals("Steve drowned", event.getDeathMessage(),
                "a death relay that edited the server's own broadcast would be changing what every "
                        + "player on the server sees");
        assertEquals(expBefore, event.getDroppedExp());
        assertTrue(event.getDrops().isEmpty());
    }

    @Test
    @DisplayName("with nobody listening, nothing happens and nothing is logged")
    void noListenersIsNotAnError() {
        new BukkitDeathListener(logger, sessions, new BukkitPlayerDirectory(INLINE, null))
                .onDeath(death("Steve was slain by Alex"));

        assertTrue(deaths.isEmpty());
        assertTrue(logger.records().isEmpty(),
                "a death on a server with the bridge module off must be silent, and in particular "
                        + "must not put the death message in a log file: " + logger.records());
    }
}
