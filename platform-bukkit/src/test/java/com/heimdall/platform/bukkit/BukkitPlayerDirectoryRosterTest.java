package com.heimdall.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.platform.PlayerHandle;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading the online list from a thread that does not own it.
 *
 * <p>{@code Bukkit.getOnlinePlayers()} returns a live transforming view over {@code PlayerList}'s
 * plain {@code ArrayList}, mutated on the main thread whenever anybody joins or quits. Every caller
 * of this directory is a tunnel handler on {@code heimdall-io}, so the race is the normal condition
 * rather than an exotic one, and the class used to claim in its own javadoc that it could not happen.
 *
 * <p>What makes it worth a test rather than a comment is where the exception lands: escaping,
 * a {@code ConcurrentModificationException} reaches {@code RemoteRequestWiring}, which turns it into
 * {@code {players: [], error: …}} — and the dashboard renders that as an empty Online Players panel
 * on a server with forty people on it. That is the same symptom as the missing-handler bug the panel
 * already suffered once, which is exactly why "it will almost never happen" is not good enough.
 *
 * <p>The static {@code Bukkit.getOnlinePlayers()} is reached through
 * {@link BukkitPlayerDirectory.RosterSource}, a seam of one method, for the same reason
 * {@code BukkitConsoleBridge.CommandSink} exists: the branch under test is otherwise reachable only
 * through a running server.
 */
class BukkitPlayerDirectoryRosterTest {

    private static final Executor INLINE = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    /** A view that throws on its first {@code attemptsToFail} reads, then yields one player. */
    private static final class RacingRoster implements BukkitPlayerDirectory.RosterSource {

        private final AtomicInteger remainingFailures;
        private final AtomicInteger reads = new AtomicInteger();
        private final RuntimeException race;

        RacingRoster(int attemptsToFail, RuntimeException race) {
            this.remainingFailures = new AtomicInteger(attemptsToFail);
            this.race = race;
        }

        @Override
        public Collection<? extends Player> onlinePlayers() {
            reads.incrementAndGet();
            if (remainingFailures.getAndDecrement() > 0) {
                throw race;
            }
            Player steve = mock(Player.class);
            when(steve.getUniqueId()).thenReturn(UUID.randomUUID());
            when(steve.getName()).thenReturn("Steve");
            return Collections.singletonList(steve);
        }
    }

    private static BukkitPlayerDirectory directoryOver(BukkitPlayerDirectory.RosterSource roster) {
        return new BukkitPlayerDirectory(INLINE, null, roster);
    }

    @Test
    @DisplayName("a join landing mid-snapshot is retried, not surfaced")
    void aConcurrentModificationIsRetried() {
        RacingRoster roster = new RacingRoster(2, new ConcurrentModificationException());

        Collection<PlayerHandle> online = directoryOver(roster).onlinePlayers();

        assertEquals(1, online.size(),
                "escaping, this becomes {players: [], error: ...} and the panel shows an empty "
                        + "server");
        assertEquals(3, roster.reads.get(), "two races, then the read that won");
    }

    @Test
    @DisplayName("the other shape of the same race is retried too")
    void anIndexOutOfBoundsIsRetried() {
        // How the race presents on the server generations where the transforming view's size() and
        // get() disagree for an instant rather than raising a CME.
        RacingRoster roster =
                new RacingRoster(1, new IndexOutOfBoundsException("Index 4 out of bounds"));

        assertEquals(1, directoryOver(roster).onlinePlayers().size());
    }

    @Test
    @DisplayName("a race that never settles is reported, NOT answered as an empty server")
    void anUnendingRaceThrowsRatherThanLying() {
        RacingRoster roster = new RacingRoster(99, new ConcurrentModificationException());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> directoryOver(roster).onlinePlayers());

        assertTrue(thrown.getCause() instanceof ConcurrentModificationException);
        assertEquals(5, roster.reads.get(), "bounded — a race that will not settle must not spin");
        // "Nobody is online" is an ordinary state that callers render as such, so a failure disguised
        // as one is a wrong answer nothing downstream can detect. PlayerDirectory now says so.
    }

    @Test
    @DisplayName("a server that is not up yet answers empty rather than throwing")
    void aServerThatIsNotReadyAnswersEmpty() {
        // Distinct from a race, and the distinction is the point: asked before the server finished
        // starting or after it began stopping, "nobody" is the true answer rather than a disguise.
        BukkitPlayerDirectory.RosterSource notReady = new BukkitPlayerDirectory.RosterSource() {
            @Override
            public Collection<? extends Player> onlinePlayers() {
                throw new IllegalStateException("server not running");
            }
        };

        assertTrue(directoryOver(notReady).onlinePlayers().isEmpty());
    }

    @Test
    @DisplayName("the ordinary path reads once")
    void aQuietServerReadsOnce() {
        RacingRoster roster = new RacingRoster(0, new ConcurrentModificationException());

        assertEquals(1, directoryOver(roster).onlinePlayers().size());
        assertEquals(1, roster.reads.get(), "the retry must cost nothing when there is no race");
    }
}
