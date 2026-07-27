package com.heimdall.core.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.json.Payload;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The correlation map's one promise: every future completes, and no timeout outlives its request.
 */
class PendingRequestsTest {

    private final CapturingScheduler scheduler = new CapturingScheduler();
    private final PendingRequests pending = new PendingRequests(scheduler);

    @Test
    @DisplayName("a reply cancels the timeout that was armed for it")
    void aReplyCancelsItsTimeout() {
        CompletableFuture<Payload> future = pending.register("abc", "get_players", 30_000L);
        assertTrue(scheduler.latestIsArmed(), "a deadline should have been armed");

        assertTrue(pending.complete("abc", Payload.builder().put("ok", true).build()));

        assertFalse(scheduler.latestIsArmed(),
                "v2 left every timeout sitting on the scheduler for its full deadline however "
                        + "quickly the request was answered; with removeOnCancel set, cancelling "
                        + "drops it from the queue at once, so a burst of fast requests leaves a "
                        + "queue that drains rather than one that grows");
        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
    }

    @Test
    @DisplayName("the timeout fires when no reply arrives")
    void anUnansweredRequestTimesOut() {
        CompletableFuture<Payload> future = pending.register("abc", "get_players", 30_000L);

        scheduler.runLatest();

        assertTrue(future.isCompletedExceptionally());
        assertEquals(0, pending.size());
    }

    @Test
    @DisplayName("forgetting a request cancels its timeout too")
    void forgettingCancelsTheTimeout() {
        pending.register("abc", "run_command", 30_000L);

        pending.forget("abc");

        assertFalse(scheduler.latestIsArmed());
        assertEquals(0, pending.size());
    }

    @Test
    @DisplayName("failAll completes everything outstanding and disarms every timeout")
    void failAllLeavesNothingBehind() {
        CompletableFuture<Payload> first = pending.register("a", "get_players", 30_000L);
        CompletableFuture<Payload> second = pending.register("b", "run_command", 30_000L);

        pending.failAll("tunnel disconnected");

        assertTrue(first.isCompletedExceptionally());
        assertTrue(second.isCompletedExceptionally());
        assertEquals(0, pending.size());
        assertFalse(scheduler.latestIsArmed());
    }

    @Test
    @DisplayName("a reply for an id nobody is waiting on is reported as unhandled")
    void anUnknownIdIsNotAReply() {
        assertFalse(pending.complete("never-registered", Payload.empty()),
                "this is how the dispatcher tells a reply from an unsolicited message");
    }
}
