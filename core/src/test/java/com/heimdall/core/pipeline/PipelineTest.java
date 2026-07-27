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
    @DisplayName("(S6) equal priorities keep their registration order across chain rebuilds")
    void tiesAreStableAcrossRebuilds() {
        LoginPipeline pipeline = new LoginPipeline(logger);
        final List<String> order = new ArrayList<String>();

        pipeline.register(attempt -> {
            order.add("first");
            return Verdict.abstain();
        }, 50, "a");
        pipeline.register(attempt -> {
            order.add("second");
            return Verdict.abstain();
        }, 50, "b");

        // The chain is rebuilt on every module enable and disable, so the interesting question is
        // not whether one sort is stable but whether the order survives being re-sorted repeatedly
        // with unrelated entries appearing and vanishing around it.
        for (int i = 0; i < 5; i++) {
            Registration noise = pipeline.register(attempt -> Verdict.abstain(), 50, "noise");
            noise.close();
        }

        order.clear();
        pipeline.dispatch(steve());

        assertEquals(Arrays.asList("first", "second"), order,
                "a ban check and a whitelist gate that swapped places because an unrelated module "
                        + "was toggled would show players the wrong reason for being kept out");
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
    @DisplayName("an interceptor that throws abstains by default, and is reported against its module")
    void aThrowingInterceptorAbstainsByDefault() {
        LoginPipeline pipeline = new LoginPipeline(logger);
        pipeline.register(attempt -> {
            throw new IllegalStateException("broken check");
        }, 1, "whitelist");
        pipeline.register(attempt -> Verdict.deny(Msg.legacy("§cbanned")), 2, "punishments");

        Verdict verdict = pipeline.dispatch(steve());

        assertTrue(verdict.isDeny(), "a broken check must neither lock everyone out nor wave "
                + "everyone through — the stricter check behind it still decides");
        assertTrue(logger.logged(LogLevel.SEVERE, "'whitelist'"),
                "an operator has to be told WHICH gate is silently not running; the class name of "
                        + "a lambda tells them nothing");
    }

    @Test
    @DisplayName("an interceptor can declare that its failure means DENY, and the pipeline honours it")
    void aFailClosedInterceptorKeepsPlayersOut() {
        LoginPipeline pipeline = new LoginPipeline(logger);
        pipeline.register(new Interceptor<LoginAttempt>() {
            @Override
            public Verdict intercept(LoginAttempt context) {
                throw new IllegalStateException("something unexpected");
            }

            @Override
            public Verdict failureVerdict(RuntimeException cause) {
                return Verdict.deny(Msg.legacy("§cwhitelist unavailable"));
            }
        }, 1, "whitelist");

        Verdict verdict = pipeline.dispatch(steve());

        assertTrue(verdict.isDeny());
        assertEquals("§cwhitelist unavailable", Msg.toLegacy(verdict.reason()));
    }

    @Test
    @DisplayName("an interceptor whose failure handling ALSO throws is treated as abstaining")
    void aDoublyBrokenInterceptorCannotWedgeTheChain() {
        LoginPipeline pipeline = new LoginPipeline(logger);
        pipeline.register(new Interceptor<LoginAttempt>() {
            @Override
            public Verdict intercept(LoginAttempt context) {
                throw new IllegalStateException("broken check");
            }

            @Override
            public Verdict failureVerdict(RuntimeException cause) {
                throw new IllegalStateException("broken failure handling too");
            }
        }, 1, "whitelist");

        assertFalse(pipeline.dispatch(steve()).isDeny(),
                "at that point nothing the check says can be trusted, and the pipeline still has to "
                        + "return an answer");
        assertTrue(logger.logged(LogLevel.SEVERE, "also threw"));
    }

    @Test
    @DisplayName("a null failure verdict is an abstain rather than a crash on the login thread")
    void aNullFailureVerdictAbstains() {
        LoginPipeline pipeline = new LoginPipeline(logger);
        pipeline.register(new Interceptor<LoginAttempt>() {
            @Override
            public Verdict intercept(LoginAttempt context) {
                throw new IllegalStateException("broken check");
            }

            @Override
            public Verdict failureVerdict(RuntimeException cause) {
                return null;
            }
        }, 1, "whitelist");

        assertFalse(pipeline.dispatch(steve()).isDeny());
    }

    @Test
    @DisplayName("a chat interceptor that throws follows the same policy")
    void chatInterceptorsShareTheFailurePolicy() {
        ChatPipeline pipeline = new ChatPipeline(logger);
        final List<String> relayed = new ArrayList<String>();
        pipeline.register(message -> {
            throw new IllegalStateException("broken filter");
        }, 1, "chat-filter");
        pipeline.observe(message -> relayed.add(message.message()));

        Verdict verdict = pipeline.dispatchWithObservers(hello());

        assertFalse(verdict.isDeny(), "a broken chat filter must not start censoring everything");
        assertEquals(Collections.singletonList("hello"), relayed);
        assertTrue(logger.logged(LogLevel.SEVERE, "'chat-filter'"));
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
        for (Class<?> type = ChatPipeline.class; type != null && type != Object.class;
                type = type.getSuperclass()) {
            // getDeclaredMethods, walked up the hierarchy: getMethods() misses package-private and
            // protected members, and a buffer added to the shared Pipeline base class would be just
            // as much of a chat log as one added here.
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || mentionsChatMessage(method.getGenericReturnType())) {
                    if (!method.isSynthetic()) {
                        accessors.add(type.getSimpleName() + "." + method.getName());
                    }
                }
            }
        }
        assertEquals(Collections.emptyList(), accessors,
                "nothing in the chat pipeline may hand back a message it has already dispatched — "
                        + "not directly, not in a collection, not in an Optional, not in an array");
    }

    /**
     * Whether a return type could carry a {@link ChatMessage} out of the pipeline.
     *
     * <p>Looks through generics and arrays rather than at the raw type alone, so
     * {@code List<ChatMessage>}, {@code Optional<ChatMessage>} and {@code ChatMessage[]} are all
     * caught — the raw types of the first two are {@code List} and {@code Optional}, which a
     * check on the erased return type would wave straight through.
     */
    private static boolean mentionsChatMessage(java.lang.reflect.Type type) {
        if (type instanceof Class) {
            Class<?> raw = (Class<?>) type;
            return ChatMessage.class.isAssignableFrom(raw)
                    || (raw.isArray() && mentionsChatMessage(raw.getComponentType()));
        }
        if (type instanceof java.lang.reflect.ParameterizedType) {
            for (java.lang.reflect.Type argument
                    : ((java.lang.reflect.ParameterizedType) type).getActualTypeArguments()) {
                if (mentionsChatMessage(argument)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof java.lang.reflect.GenericArrayType) {
            return mentionsChatMessage(
                    ((java.lang.reflect.GenericArrayType) type).getGenericComponentType());
        }
        if (type instanceof java.lang.reflect.WildcardType) {
            for (java.lang.reflect.Type bound
                    : ((java.lang.reflect.WildcardType) type).getUpperBounds()) {
                if (mentionsChatMessage(bound)) {
                    return true;
                }
            }
        }
        return false;
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
