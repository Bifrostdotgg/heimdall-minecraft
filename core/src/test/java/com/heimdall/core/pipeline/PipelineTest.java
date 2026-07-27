package com.heimdall.core.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.text.Msg;
import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ordering, short-circuiting, the abstain distinction, and the relay-only shape of chat. */
class PipelineTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    private static LoginAttempt steve() {
        return LoginAttempt.builder(UUID.fromString("11111111-2222-3333-4444-555555555555"))
                .username("Steve")
                .ipAddress("1.2.3.4")
                .build();
    }

    private static ChatMessage hello() {
        return ChatMessage.of(UUID.fromString("11111111-2222-3333-4444-555555555555"), "Steve", "hello");
    }

    // ── Ordering and short-circuiting ────────────────────────────────────────

    @Test
    @DisplayName("interceptors run in priority order, and ties break by registration order")
    void orderingIsExplicitAndStable() {
        LoginPipeline pipeline = new LoginPipeline(logger);
        final List<String> order = new ArrayList<String>();

        pipeline.register(attempt -> {
            order.add("late");
            return Verdict.abstain();
        }, 100);
        pipeline.register(attempt -> {
            order.add("early");
            return Verdict.abstain();
        }, 1);
        pipeline.register(attempt -> {
            order.add("tie-first");
            return Verdict.abstain();
        }, 50);
        pipeline.register(attempt -> {
            order.add("tie-second");
            return Verdict.abstain();
        }, 50);

        pipeline.dispatch(steve());

        assertEquals(Arrays.asList("early", "tie-first", "tie-second", "late"), order,
                "a whitelist gate and a ban check that swapped places because the dashboard toggled "
                        + "them in a different order would show players the wrong reason for being "
                        + "kept out");
    }

    @Test
    @DisplayName("the first deny stops the chain and nothing after it can overturn it")
    void theFirstDenyWins() {
        LoginPipeline pipeline = new LoginPipeline(logger);
        final List<String> ran = new ArrayList<String>();

        pipeline.register(attempt -> {
            ran.add("first");
            return Verdict.deny(Msg.legacy("§cnot whitelisted"));
        }, 1);
        pipeline.register(attempt -> {
            ran.add("second");
            return Verdict.allow();
        }, 2);

        Verdict verdict = pipeline.dispatch(steve());

        assertTrue(verdict.isDeny());
        assertEquals("§cnot whitelisted", Msg.toLegacy(verdict.reason()));
        assertEquals(Collections.singletonList("first"), ran);
    }

    @Test
    @DisplayName("an ALLOW does not stop the chain — a later interceptor may still deny")
    void allowIsNotTerminal() {
        LoginPipeline pipeline = new LoginPipeline(logger);

        pipeline.register(attempt -> Verdict.allow(), 1);
        pipeline.register(attempt -> Verdict.deny(Msg.legacy("§cbanned")), 2);

        assertTrue(pipeline.dispatch(steve()).isDeny(),
                "a satisfied whitelist check must not be able to wave a banned player through");
    }

    @Test
    @DisplayName("abstain is not allow — an indifferent check cannot veto a stricter one")
    void abstainIsNotAllow() {
        LoginPipeline pipeline = new LoginPipeline(logger);

        pipeline.register(attempt -> Verdict.abstain(), 1);
        pipeline.register(attempt -> Verdict.deny(Msg.legacy("§cbanned")), 2);

        assertTrue(pipeline.dispatch(steve()).isDeny(),
                "collapsing abstain into allow means the first switched-off module silently vetoes "
                        + "every stricter check behind it, and nothing in any log would say so");
    }

    @Test
    @DisplayName("an empty or all-abstain login pipeline admits the player")
    void theLoginDefaultIsAllow() {
        LoginPipeline pipeline = new LoginPipeline(logger);
        assertFalse(pipeline.dispatch(steve()).isDeny(),
                "a pipeline with nothing registered is the pipeline on a server with the whitelist "
                        + "module switched off, and that server has to let people in");

        pipeline.register(attempt -> Verdict.abstain(), 1);
        assertFalse(pipeline.dispatch(steve()).isDeny());
    }

    @Test
    @DisplayName("an interceptor that throws is treated as having abstained")
    void athrowingInterceptorAbstains() {
        LoginPipeline pipeline = new LoginPipeline(logger);
        pipeline.register(attempt -> {
            throw new IllegalStateException("broken check");
        }, 1);
        pipeline.register(attempt -> Verdict.deny(Msg.legacy("§cbanned")), 2);

        Verdict verdict = pipeline.dispatch(steve());

        assertTrue(verdict.isDeny(), "a broken check must neither lock everyone out nor wave "
                + "everyone through — the stricter check behind it still decides");
        assertTrue(logger.logged(LogLevel.SEVERE, "login interceptor at priority 1 threw"));
    }

    @Test
    @DisplayName("a null verdict is treated as an abstain rather than a crash on the login thread")
    void aNullVerdictAbstains() {
        LoginPipeline pipeline = new LoginPipeline(logger);
        pipeline.register(attempt -> null, 1);

        assertFalse(pipeline.dispatch(steve()).isDeny());
    }

    @Test
    void registrationsCanBeClosed() {
        LoginPipeline pipeline = new LoginPipeline(logger);
        Registration registration = pipeline.register(attempt -> Verdict.deny(Msg.legacy("§cno")), 1);
        assertEquals(1, pipeline.size());

        registration.close();
        registration.close();

        assertEquals(0, pipeline.size());
        assertFalse(pipeline.dispatch(steve()).isDeny());
    }

    @Test
    @DisplayName("dispatch runs synchronously on the calling thread")
    void dispatchIsSynchronous() {
        LoginPipeline pipeline = new LoginPipeline(logger);
        final AtomicReference<String> thread = new AtomicReference<String>();
        pipeline.register(attempt -> {
            thread.set(Thread.currentThread().getName());
            return Verdict.abstain();
        }, 1);

        pipeline.dispatch(steve());

        assertEquals(Thread.currentThread().getName(), thread.get(),
                "platform pre-login threads are async-safe and an interceptor that consults the bot "
                        + "needs the answer now; hopping pools to wait would change nothing");
    }

    @Test
    void aPipelineCannotFallThroughToAnotherAbstain() {
        assertThrows(IllegalArgumentException.class,
                () -> new Pipeline<LoginAttempt>("bad", logger, Verdict.Decision.ABSTAIN) {
                });
    }

    // ── Chat ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("observers run for allowed messages, in registration order")
    void observersRunForAllowedMessages() {
        ChatPipeline pipeline = new ChatPipeline(logger);
        final List<String> seen = new ArrayList<String>();
        pipeline.observe(message -> seen.add("first:" + message.message()));
        pipeline.observe(message -> seen.add("second:" + message.message()));

        Verdict verdict = pipeline.dispatchWithObservers(hello());

        assertFalse(verdict.isDeny());
        assertEquals(Arrays.asList("first:hello", "second:hello"), seen);
    }

    @Test
    @DisplayName("a blocked message is NOT relayed")
    void observersDoNotSeeBlockedMessages() {
        ChatPipeline pipeline = new ChatPipeline(logger);
        final List<String> seen = new ArrayList<String>();
        pipeline.register(message -> Verdict.deny(Msg.legacy("§cblocked")), 1);
        pipeline.observe(message -> seen.add(message.message()));

        assertTrue(pipeline.dispatchWithObservers(hello()).isDeny());
        assertTrue(seen.isEmpty(),
                "relaying a censored message to Discord would put it in front of a wider audience "
                        + "than the one it was just hidden from");
    }

    @Test
    @DisplayName("an observer that throws does not block the message or stop the other observers")
    void aThrowingObserverIsContained() {
        ChatPipeline pipeline = new ChatPipeline(logger);
        final List<String> seen = new ArrayList<String>();
        pipeline.observe(message -> {
            throw new IllegalStateException("relay is down");
        });
        pipeline.observe(message -> seen.add(message.message()));

        Verdict verdict = pipeline.dispatchWithObservers(hello());

        assertFalse(verdict.isDeny(), "a broken relay must not start censoring chat");
        assertEquals(Collections.singletonList("hello"), seen);
        assertTrue(logger.logged(LogLevel.SEVERE, "chat observer threw"));
    }

    @Test
    void chatObserversCanBeUnregistered() {
        ChatPipeline pipeline = new ChatPipeline(logger);
        final List<String> seen = new ArrayList<String>();
        Registration registration = pipeline.observe(message -> seen.add(message.message()));
        assertEquals(1, pipeline.observerCount());

        registration.close();
        pipeline.dispatchWithObservers(hello());

        assertEquals(0, pipeline.observerCount());
        assertTrue(seen.isEmpty());
    }

    @Test
    @DisplayName("the chat API exposes no way to read back a message it has already dispatched")
    void chatIsStructurallyRelayOnly() {
        // Relay-only forever is a product decision, and the API is shaped so that violating it
        // means ADDING a method rather than calling one. This test is the executable form of that
        // claim: if a buffer, a history or a getLast ever appears, it fails.
        List<String> accessors = new ArrayList<String>();
        for (java.lang.reflect.Method method : ChatPipeline.class.getMethods()) {
            if (method.getDeclaringClass() != ChatPipeline.class) {
                continue;
            }
            if (ChatMessage.class.isAssignableFrom(method.getReturnType())
                    || Iterable.class.isAssignableFrom(method.getReturnType())) {
                accessors.add(method.getName());
            }
        }
        assertEquals(Collections.emptyList(), accessors,
                "ChatPipeline must never return a message, or a collection that could hold one");
    }

    @Test
    @DisplayName("neither event value renders its sensitive field in toString")
    void toStringOmitsWhatLogsShouldNotCarry() {
        assertFalse(steve().toString().contains("1.2.3.4"),
                "a login line carrying a player's address is personal data in a file operators paste "
                        + "into support tickets");
        assertFalse(hello().toString().contains("hello"),
                "chat content reaching a log file is exactly the storage this feature promises not "
                        + "to do");
    }

    @Test
    void verdictFactoriesAreShared() {
        assertSame(Verdict.allow(), Verdict.allow());
        assertSame(Verdict.abstain(), Verdict.abstain());
        assertEquals(Verdict.Decision.DENY, Verdict.deny(null).decision());
        assertEquals("", Msg.toLegacy(Verdict.deny(null).reason()));
    }
}
