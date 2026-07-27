package com.heimdall.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The join/quit seam: separation, containment, hand-off, and the handle that undoes it.
 *
 * <p>Most of these run on a same-thread executor, because what is being pinned is the routing rather
 * than the asynchrony. {@link #handlersLeaveTheEventThread} is the exception and uses a real pool —
 * it is the one property the same-thread executor cannot show, and the one the whole design exists
 * for.
 */
class PlayerSessionEventsTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    private static final Executor INLINE = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private static PlayerSessionListener collectingInto(final List<String> sink) {
        return new PlayerSessionListener() {
            @Override
            public void onPlayerSession(PlayerHandle player, long timestampMs) {
                sink.add(player.name() + "@" + timestampMs);
            }
        };
    }

    @Test
    @DisplayName("a join listener does not hear quits, and the other way round")
    void joinAndQuitAreSeparate() {
        PlayerSessionEvents events = new PlayerSessionEvents(logger, INLINE);
        List<String> joins = new ArrayList<String>();
        List<String> quits = new ArrayList<String>();
        events.onJoin(collectingInto(joins));
        events.onQuit(collectingInto(quits));

        events.join(FakePlayer.named("Steve"), 100L);
        events.quit(FakePlayer.named("Alex"), 200L);

        assertEquals(java.util.Collections.singletonList("Steve@100"), joins);
        assertEquals(java.util.Collections.singletonList("Alex@200"), quits);
    }

    @Test
    @DisplayName("the timestamp is the platform's, not the dispatcher's")
    void timestampIsCarried() {
        PlayerSessionEvents events = new PlayerSessionEvents(logger, INLINE);
        final AtomicReference<Long> seen = new AtomicReference<Long>();
        events.onJoin(new PlayerSessionListener() {
            @Override
            public void onPlayerSession(PlayerHandle player, long timestampMs) {
                seen.set(Long.valueOf(timestampMs));
            }
        });

        // A value that cannot be confused with a clock read, which is exactly the substitution the
        // mirror's window rule would silently tolerate and this pins against.
        events.join(FakePlayer.named("Steve"), 1234L);

        assertEquals(Long.valueOf(1234L), seen.get());
    }

    @Test
    @DisplayName("closing the handle stops the listener, and closing twice is a no-op")
    void registrationIsIdempotent() {
        PlayerSessionEvents events = new PlayerSessionEvents(logger, INLINE);
        List<String> joins = new ArrayList<String>();
        Registration handle = events.onJoin(collectingInto(joins));

        events.join(FakePlayer.named("Steve"), 1L);
        handle.close();
        handle.close();
        events.join(FakePlayer.named("Alex"), 2L);

        assertEquals(java.util.Collections.singletonList("Steve@1"), joins);
        assertEquals(0, events.joinListenerCount());
    }

    @Test
    @DisplayName("one broken listener does not stop the listeners after it")
    void aBrokenListenerIsContained() {
        PlayerSessionEvents events = new PlayerSessionEvents(logger, INLINE);
        List<String> reached = new ArrayList<String>();
        events.onJoin(new PlayerSessionListener() {
            @Override
            public void onPlayerSession(PlayerHandle player, long timestampMs) {
                throw new IllegalStateException("this module is broken");
            }
        });
        events.onJoin(collectingInto(reached));

        events.join(FakePlayer.named("Steve"), 1L);

        assertEquals(java.util.Collections.singletonList("Steve@1"), reached,
                "the whitelist mirror must still be extended when somebody else's handler throws");
        assertTrue(logger.logged(LogLevel.SEVERE, "join listener failed"),
                "and the failure has to be attributable: " + logger.records());
    }

    @Test
    @DisplayName("handlers run off the thread that reported the event")
    void handlersLeaveTheEventThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            PlayerSessionEvents events = new PlayerSessionEvents(logger, pool);
            final CountDownLatch delivered = new CountDownLatch(1);
            final AtomicReference<String> handlerThread = new AtomicReference<String>();
            events.onJoin(new PlayerSessionListener() {
                @Override
                public void onPlayerSession(PlayerHandle player, long timestampMs) {
                    handlerThread.set(Thread.currentThread().getName());
                    delivered.countDown();
                }
            });

            String reportingThread = Thread.currentThread().getName();
            events.join(FakePlayer.named("Steve"), 1L);

            assertTrue(delivered.await(5, TimeUnit.SECONDS), "the handler never ran");
            assertFalse(reportingThread.equals(handlerThread.get()),
                    "PlayerJoinEvent is on the main server thread; a handler must not be");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a rejected hand-off is dropped, not thrown at the platform")
    void rejectedHandOffIsDropped() {
        PlayerSessionEvents events = new PlayerSessionEvents(logger, new Executor() {
            @Override
            public void execute(Runnable command) {
                throw new RejectedExecutionException("shutting down");
            }
        });
        events.onJoin(collectingInto(new ArrayList<String>()));

        // The event thread is Bukkit's; an exception here is logged by the server as a plugin fault
        // for something that only happened because the plugin was already stopping.
        events.join(FakePlayer.named("Steve"), 1L);

        assertTrue(logger.logged(LogLevel.DEBUG, "shutting down"), logger.records().toString());
    }

    @Test
    @DisplayName("nothing is dispatched for a null player, and no listener is optional")
    void guardsOnTheEdges() {
        PlayerSessionEvents events = new PlayerSessionEvents(logger, INLINE);
        List<String> joins = new ArrayList<String>();
        events.onJoin(collectingInto(joins));

        events.join(null, 1L);
        events.quit(null, 1L);

        assertTrue(joins.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> events.onJoin(null));
        assertThrows(IllegalArgumentException.class, () -> events.onQuit(null));
        assertThrows(IllegalArgumentException.class, () -> new PlayerSessionEvents(logger, null));
    }
}
