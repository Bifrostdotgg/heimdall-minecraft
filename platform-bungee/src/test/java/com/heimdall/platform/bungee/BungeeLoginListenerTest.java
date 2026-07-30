package com.heimdall.platform.bungee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.http.BedrockIdentity;
import com.heimdall.core.http.BedrockIdentityProvider;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.pipeline.Interceptor;
import com.heimdall.core.pipeline.LoginAttempt;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.core.text.Msg;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.md_5.bungee.api.Callback;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The login gate, against BungeeCord's real {@code AsyncEvent} intent machinery.
 *
 * <h2>Why the real event and not a stand-in</h2>
 *
 * <p>Everything this class asserts is about a promise BungeeCord makes and does not enforce: an
 * intent that is registered and never completed leaves that player's connection waiting at the login
 * screen <em>forever</em>. {@code AsyncEvent} holds a latch and a callback and no clock; nothing in
 * the proxy notices, nothing logs, and the symptom reaches support as "some players just hang".
 *
 * <p>A hand-rolled fake event would only ever agree with this test's own idea of how intents work.
 * So {@link LoginEvent} is constructed for real, with a real {@link Callback}, and
 * {@link LoginEvent#postCall()} is invoked exactly where BungeeCord's {@code EventBus} invokes it —
 * after the handler returns. The callback firing is therefore not an assertion this test makes up:
 * it is BungeeCord's own latch reaching zero, which is the same thing that lets the connection
 * proceed on a real proxy.
 *
 * <p>Every test here is a revert check. Delete the {@code finally} in the worker, or the
 * {@code complete()} on the rejection path, or the {@code compareAndSet}, and exactly one of these
 * goes red.
 */
class BungeeLoginListenerTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final RecordingLogger logger = new RecordingLogger(true);
    private final LoginPipeline pipeline = new LoginPipeline(logger);

    /**
     * A plugin instance, used only as the key BungeeCord files intents under.
     *
     * <p>Through {@code Plugin}'s <em>protected</em> constructor, which exists for exactly this and
     * says so by asserting that the classloader is <strong>not</strong> a {@code PluginClassloader}
     * — the no-arg one asserts the opposite, because at runtime a plugin is only ever constructed by
     * the loader. Every accessor on the result is null, which is fine:
     * {@code registerIntent}/{@code completeIntent} put it in a {@code ConcurrentHashMap} and never
     * touch it otherwise.
     */
    private static final class TestPlugin extends Plugin {
        TestPlugin() {
            super(null, null);
        }
    }

    private final Plugin plugin = new TestPlugin();

    /** Runs the deferred decision on the calling thread, so assertions are ordinary. */
    private static final Executor INLINE = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    /** Floodgate absent, which is the ordinary case and not what any of this is about. */
    private static final BedrockIdentityProvider NO_FLOODGATE = new BedrockIdentityProvider() {
        @Override
        public BedrockIdentity resolve(String uuid) {
            return null;
        }
    };

    /** Records what {@link LoginEvent#postCall()} handed back — BungeeCord's own release signal. */
    private static final class Gate implements Callback<LoginEvent> {

        private final AtomicInteger fired = new AtomicInteger();
        private final AtomicReference<LoginEvent> released = new AtomicReference<LoginEvent>();

        @Override
        public void done(LoginEvent result, Throwable error) {
            fired.incrementAndGet();
            released.set(result);
        }

        boolean released() {
            return fired.get() > 0;
        }

        int releases() {
            return fired.get();
        }

        LoginEvent event() {
            return released.get();
        }
    }

    /** A {@link PendingConnection} with only the three things the listener reads. */
    private static PendingConnection connection(final UUID uuid, final String name) {
        return (PendingConnection) java.lang.reflect.Proxy.newProxyInstance(
                PendingConnection.class.getClassLoader(),
                new Class<?>[] {PendingConnection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId":
                            return uuid;
                        case "getName":
                            return name;
                        case "getAddress":
                            return new InetSocketAddress(
                                    InetAddress.getByAddress(new byte[] {10, 0, 0, 7}), 25565);
                        case "toString":
                            return "PendingConnection{" + name + "}";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return null;
                    }
                });
    }

    private BungeeLoginListener listenerOn(Executor executor) {
        return new BungeeLoginListener(
                plugin, logger, pipeline, NO_FLOODGATE, new BungeeText(), executor);
    }

    /** Registers an interceptor and returns the attempts it saw. */
    private List<LoginAttempt> record(final Verdict verdict) {
        final List<LoginAttempt> seen = new ArrayList<LoginAttempt>();
        pipeline.register(new Interceptor<LoginAttempt>() {
            @Override
            public Verdict intercept(LoginAttempt context) {
                seen.add(context);
                return verdict;
            }
        }, 0, "test");
        return seen;
    }

    /** Drives one login exactly as BungeeCord's EventBus does: handler, then postCall(). */
    private LoginEvent drive(BungeeLoginListener listener, Gate gate, PendingConnection connection) {
        LoginEvent event = new LoginEvent(connection, gate);
        listener.onLogin(event);
        event.postCall();
        return event;
    }

    @Test
    @DisplayName("an allowed login completes its intent, so the connection proceeds")
    void allowCompletesTheIntent() {
        record(Verdict.allow());
        Gate gate = new Gate();

        LoginEvent event = drive(listenerOn(INLINE), gate, connection(PLAYER, "AllowedSteve"));

        assertFalse(event.isCancelled());
        assertTrue(gate.released(),
                "the intent was never completed, so this player's connection is hung at the login "
                        + "screen with nothing anywhere to time it out");
        assertEquals(1, gate.releases(), "exactly once");
    }

    @Test
    @DisplayName("a denied login completes its intent too, and carries the reason")
    void denyCompletesTheIntentAndSetsTheReason() {
        record(Verdict.deny(Msg.legacy("§cYou are not whitelisted.")));
        Gate gate = new Gate();

        LoginEvent event = drive(listenerOn(INLINE), gate, connection(PLAYER, "DeniedSteve"));

        assertTrue(gate.released(),
                "a refusal that never releases the gate is not a refusal — the player sees a hang "
                        + "rather than a kick screen, and never learns why");
        assertTrue(event.isCancelled(), "cancelled, or BungeeCord ignores the reason entirely");

        BaseComponent[] reason = event.getCancelReasonComponents();
        assertNotNull(reason, "a denial with no reason shows BungeeCord's default kick message");
        assertTrue(TextComponent.toLegacyText(reason).contains("not whitelisted"),
                "the dashboard's message has to survive the trip: "
                        + TextComponent.toLegacyText(reason));
    }

    @Test
    @DisplayName("the cancel flag is set as well as the reason, in that order")
    void cancellationIsWhatBungeeReads() {
        // The near miss worth pinning: setCancelReason alone leaves isCancelled() false, and
        // InitialHandler only looks at the reason once the flag says to. A gate that set the text
        // and not the flag would refuse nobody while looking, in the log and in the code, exactly
        // like a gate that worked.
        record(Verdict.deny(Msg.legacy("§cno")));
        Gate gate = new Gate();

        LoginEvent event = drive(listenerOn(INLINE), gate, connection(PLAYER, "DeniedSteve"));

        assertTrue(event.isCancelled());
    }

    @Test
    @DisplayName("a pipeline that throws past its own containment still completes the intent")
    void athrowingPipelineCompletesTheIntent() {
        // An Error, deliberately: Pipeline.dispatch catches RuntimeException per interceptor and
        // applies the declared failure verdict (D39), so a RuntimeException never reaches the
        // listener's own catch. What does reach it is the NoSuchMethodError class of failure, which
        // is the whole reason that catch is on Throwable.
        pipeline.register(new Interceptor<LoginAttempt>() {
            @Override
            public Verdict intercept(LoginAttempt context) {
                throw new NoSuchMethodError("an API that moved between proxy versions");
            }
        }, 0, "broken");
        Gate gate = new Gate();

        LoginEvent event = drive(listenerOn(INLINE), gate, connection(PLAYER, "Steve"));

        assertTrue(gate.released(),
                "a bug in the glue must not leave a connection hostage");
        assertFalse(event.isCancelled(),
                "and it must not lock the network either — the pipeline's default for login is admit");
        assertTrue(logger.logged(LogLevel.SEVERE, "admitting them"),
                "the operator has to be told the gate did not run: " + logger.records());
    }

    @Test
    @DisplayName("an executor that refuses the work completes the intent rather than hanging")
    void rejectedExecutionCompletesTheIntent() {
        // The plugin is being disabled while somebody is mid-login: heimdall-io has stopped
        // accepting tasks. The intent is already registered by then, so the only correct thing left
        // is to release it — the alternative is a player who never gets in and never gets kicked.
        record(Verdict.deny(Msg.legacy("§cno")));
        Gate gate = new Gate();
        Executor shuttingDown = new Executor() {
            @Override
            public void execute(Runnable command) {
                throw new RejectedExecutionException("heimdall-io is shutting down");
            }
        };

        LoginEvent event = drive(listenerOn(shuttingDown), gate, connection(PLAYER, "Steve"));

        assertTrue(gate.released(), "the gate has to be released even when the check never ran");
        assertFalse(event.isCancelled(),
                "and the player is admitted: refusing on the strength of a check that did not "
                        + "happen would lock a network out during every restart");
        assertTrue(logger.logged(LogLevel.WARN, "never come"), logger.records().toString());
    }

    @Test
    @DisplayName("an executor that runs the task and then reports it rejected releases the gate once")
    void theGateIsReleasedAtMostOnce() {
        // The at-most-once half of the contract, which the AtomicBoolean is the whole of.
        // completeIntent checkStates that an intent is outstanding, so a second call throws — on the
        // netty event loop, inside BungeeCord's own dispatch, for a connection already let through.
        // An executor that runs the task and THEN throws is that shape, and it is not something a
        // caller can rule out about somebody else's pool.
        record(Verdict.allow());
        Gate gate = new Gate();
        Executor ranItAnyway = new Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
                throw new RejectedExecutionException("reported after running it");
            }
        };

        LoginEvent event = drive(listenerOn(ranItAnyway), gate, connection(PLAYER, "Steve"));

        assertFalse(event.isCancelled());
        assertEquals(1, gate.releases(), "the gate must be released exactly once");
        assertFalse(logger.logged(LogLevel.SEVERE, "could not release the login gate"),
                "a second completeIntent throws inside BungeeCord's event dispatch: "
                        + logger.records());
    }

    @Test
    @DisplayName("a connection another plugin already refused is left entirely alone")
    void alreadyCancelledIsSkipped() {
        // No intent is registered at all on this path, which is what the single release below
        // proves: if one were, postCall() would see a non-zero latch and the callback would not
        // fire until it was completed.
        List<LoginAttempt> seen = record(Verdict.deny(Msg.legacy("§cours")));
        Gate gate = new Gate();
        LoginEvent event = new LoginEvent(connection(PLAYER, "BannedSteve"), gate);
        event.setCancelled(true);
        event.setCancelReason(TextComponent.fromLegacyText("§cBanned until 2027 — appeal at ..."));

        listenerOn(INLINE).onLogin(event);
        event.postCall();

        assertTrue(seen.isEmpty(), "the pipeline was consulted about a decision already made");
        assertTrue(TextComponent.toLegacyText(event.getCancelReasonComponents()).contains("appeal"),
                "overwriting the reason replaces a ban's expiry and appeal text with ours, and the "
                        + "staff member who gets asked about it has no idea LiteBans was involved");
        assertEquals(1, gate.releases());
    }

    @Test
    @DisplayName("a connection with no resolved UUID is admitted, without registering an intent")
    void missingUuidIsAdmitted() {
        // Unreachable through a BungeeCord that fires LoginEvent where InitialHandler does. Checked
        // because a LoginAttempt cannot be built without a uuid, and the alternative is an exception
        // thrown after the intent has already taken the connection hostage.
        List<LoginAttempt> seen = record(Verdict.deny(Msg.legacy("§cno")));
        Gate gate = new Gate();

        LoginEvent event = drive(listenerOn(INLINE), gate, connection(null, "Nameless"));

        assertTrue(seen.isEmpty());
        assertFalse(event.isCancelled());
        assertTrue(gate.released());
    }

    @Test
    @DisplayName("the attempt carries the uuid, the name and the address the proxy saw")
    void attemptIsBuiltFromThePendingConnection() {
        List<LoginAttempt> seen = record(Verdict.allow());
        Gate gate = new Gate();

        drive(listenerOn(INLINE), gate, connection(PLAYER, "AllowedSteve"));

        assertEquals(1, seen.size());
        LoginAttempt attempt = seen.get(0);
        assertEquals(PLAYER, attempt.uuid());
        assertEquals("AllowedSteve", attempt.username());
        assertEquals("10.0.0.7", attempt.ipAddress(),
                "the connection-attempt record the bot files is keyed on this");
        assertFalse(attempt.isBedrock());
    }
}
