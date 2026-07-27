package com.heimdall.module.offenses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.heimdall.core.command.CommandSource;
import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.ApiClient;
import com.heimdall.core.http.ApiSettings;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.stubbot.StubBot;
import com.heimdall.stubbot.StubBotConfig;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /offend}, end to end: a fake server, a real {@link ApiClient}, and a real {@code StubBot}.
 *
 * <p>Nothing here mocks the bot. The escalation response is the shape most likely to be wrong — the
 * tier, the point total and, above all, the {@code command} string the server is then expected to
 * run — and a stubbed client would only prove this module agrees with whatever this repo wrote down.
 *
 * <p>Commands are dispatched through {@code RecordingCommands}, which applies the permission gate
 * before the handler the way both real registrars do; a test that called the handler directly would
 * pass on a command that leaked staff functionality to everybody.
 *
 * <p>The handler answers from {@code heimdall-io}, so the assertions poll rather than expecting the
 * reply to be there when {@code run} returns. That is the behaviour under test, not an inconvenience:
 * a handler that had already answered would be one that blocked the server thread on an HTTP round
 * trip.
 */
class OffendCommandTest {

    /** The stub's DENY fixture — the player every offense in here is filed against. */
    private static final String TARGET_UUID = "22222222-2222-2222-2222-222222222222";
    private static final String TARGET_NAME = "DeniedAlex";

    private static final long AWAIT_MS = 20_000L;

    private final RecordingLogger logger = new RecordingLogger(true);

    @TempDir
    Path dataDir;

    private StubBot bot;
    private HeimdallExecutors executors;
    private ApiClient client;
    private FakePlatform platform;
    private TestModuleContext context;
    private HeimdallOffensesModule module;
    private FakeCommandSource staff;

    @BeforeEach
    void startEverything() throws Exception {
        bot = StubBot.start(StubBotConfig.withDemoFixtures().bindHost("127.0.0.1").port(0));
        executors = new HeimdallExecutors(logger, 2);
        client = new ApiClient(logger, ApiSettings.builder()
                .baseUrl(bot.baseUrl())
                .guildId(StubBotConfig.DEFAULT_GUILD_ID)
                .apiKey(StubBotConfig.DEFAULT_API_KEY)
                .serverId("survival")
                .timeoutMs(5000)
                .retries(1)
                .retryDelayMs(25)
                .build(), executors.io());

        platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        platform.join(new FakePlayer(UUID.fromString(TARGET_UUID), TARGET_NAME));
        platform.join(FakePlayer.named("Bystander"));

        context = new TestModuleContext(HeimdallOffensesModule.ID, logger, executors, platform);
        module = new HeimdallOffensesModule(client);
        module.enable(context);
        // enable() fires a refresh but does not wait for it; the completion tests need the cache
        // populated deterministically, and a second refresh is idempotent.
        module.refreshOffenseTypes().get(20, TimeUnit.SECONDS);

        staff = FakeCommandSource.player("ModMandy").grant(OffendCommand.PERMISSION);
    }

    @AfterEach
    void stopEverything() {
        if (module != null) {
            module.disable();
        }
        if (executors != null) {
            executors.shutdown(2000);
        }
        if (bot != null) {
            bot.close();
        }
    }

    private boolean run(CommandSource source, String... args) {
        return platform.commandRegistry().run(source, OffendCommand.NAME, args);
    }

    private List<String> complete(CommandSource source, String... args) {
        return platform.commandRegistry().complete(source, OffendCommand.NAME, args);
    }

    private static void awaitTold(FakeCommandSource source, String needle) {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (source.wasTold(needle)) {
                return;
            }
            sleep();
        }
        fail("never told '" + needle + "'; got " + source.messageText());
    }

    private void awaitDispatchCount(int expected) {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (platform.dispatchedCommands().size() >= expected) {
                return;
            }
            sleep();
        }
        fail("expected " + expected + " dispatched command(s); got "
                + platform.dispatchedCommands());
    }

    /** Stops the bot mid-test and forgets it, so {@code @AfterEach} does not close it twice. */
    private void stopBot() {
        bot.close();
        bot = null;
    }

    private static void sleep() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", e);
        }
    }

    // ── The happy path ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("recording an offense")
    class HappyPath {

        @Test
        @DisplayName("the tier is reported and the bot's command is dispatched")
        void theWholeFlow() {
            assertTrue(run(staff, TARGET_NAME, "xray", "caught", "on", "camera"));

            // Waited on FIRST, and the order matters. The handler answers asynchronously and sends
            // its five lines in sequence before dispatching, so the dispatch is the only signal that
            // is strictly later than all of them. Waiting on the first line instead — "Offense
            // recorded for …" — and then asserting the rest is a race that passes locally and fails
            // on a loaded CI runner between "Type:" and "(tier 1)", which is exactly what it did.
            awaitDispatchCount(1);

            assertTrue(staff.wasTold("Offense recorded for " + TARGET_NAME),
                    staff.messageText().toString());
            assertTrue(staff.wasTold("Type: Cheating"), staff.messageText().toString());
            assertTrue(staff.wasTold("(tier 1)"), staff.messageText().toString());
            assertTrue(staff.wasTold("Total points: 1"), staff.messageText().toString());

            String dispatched = platform.dispatchedCommands().get(0);
            assertTrue(dispatched.startsWith("warn " + TARGET_NAME), dispatched);
            assertTrue(staff.wasTold("Dispatching: " + dispatched), staff.messageText().toString());
        }

        @Test
        @DisplayName("repeat offenses escalate, and the escalated command is the one dispatched")
        void escalation() {
            assertTrue(run(staff, TARGET_NAME, "xray"));
            awaitDispatchCount(1);

            staff.clearMessages();
            assertTrue(run(staff, TARGET_NAME, "exploiting"));
            awaitDispatchCount(2);

            assertTrue(staff.wasTold("Total points: 2"), staff.messageText().toString());
            assertTrue(staff.wasTold("(tier 2)"), staff.messageText().toString());
            assertTrue(platform.dispatchedCommands().get(1).startsWith("tempban " + TARGET_NAME + " 1d"),
                    platform.dispatchedCommands().toString());
        }

        @Test
        @DisplayName("the name recorded is the platform's casing, not the operator's")
        void theHandleSuppliesTheCasing() {
            assertTrue(run(staff, "deniedALEX", "xray"));

            awaitTold(staff, "Offense recorded for " + TARGET_NAME);
            awaitDispatchCount(1);
            assertTrue(platform.dispatchedCommands().get(0).startsWith("warn " + TARGET_NAME),
                    "the bot substitutes {player} from what we sent, so the wrong casing would show "
                            + "up in the punishment and in the infraction record: "
                            + platform.dispatchedCommands());
        }

        @Test
        @DisplayName("the console can issue one, and its null UUID is not an NPE")
        void theConsoleIsAValidIssuer() {
            FakeCommandSource console = FakeCommandSource.console();

            assertTrue(run(console, TARGET_NAME, "xray"));

            awaitTold(console, "Offense recorded for " + TARGET_NAME);
            awaitDispatchCount(1);
        }
    }

    // ── Everything that must not reach the bot ───────────────────────────────

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("an offline target is refused without an API call")
        void offlineTargetIsRefused() {
            assertTrue(run(staff, "GhostGary", "xray"));

            assertTrue(staff.wasTold("Could not resolve GhostGary"), staff.messageText().toString());
            assertFalse(staff.wasTold("Recording offense"),
                    "that line is printed immediately before the request, so its absence is the "
                            + "proof no request was made");
            assertEquals(Collections.<String>emptyList(), platform.dispatchedCommands());

            // The stronger proof: the next real offense is still a first offense. Had the refused
            // one reached the bot, the running total for this type would already be 1.
            staff.clearMessages();
            assertTrue(run(staff, TARGET_NAME, "xray"));
            awaitTold(staff, "Total points: 1");
        }

        @Test
        @DisplayName("too few arguments is the usage line, and nothing else")
        void tooFewArguments() {
            assertTrue(run(staff, TARGET_NAME));

            assertTrue(staff.wasTold("Usage: " + OffendCommand.USAGE), staff.messageText().toString());
            assertFalse(staff.wasTold("Recording offense"));
            assertEquals(Collections.<String>emptyList(), platform.dispatchedCommands());
        }

        @Test
        @DisplayName("a source without heimdall.offend never reaches the handler")
        void permissionIsGatedByTheRegistrar() {
            FakeCommandSource nobody = FakeCommandSource.player("Mallory");

            assertFalse(run(nobody, TARGET_NAME, "xray"),
                    "the gate is in the registrar on both real platforms, so the fake applies it too");

            assertTrue(nobody.messageText().isEmpty());
            assertEquals(Collections.<String>emptyList(), platform.dispatchedCommands());
        }

        @Test
        @DisplayName("an unknown slug is the bot's 404, reported verbatim, with nothing dispatched")
        void unknownSlug() {
            assertTrue(run(staff, TARGET_NAME, "jaywalking"));

            awaitTold(staff, "Failed to record offense");
            assertTrue(staff.wasTold("UNKNOWN_OFFENSE"), staff.messageText().toString());
            assertEquals(Collections.<String>emptyList(), platform.dispatchedCommands());
        }

        @Test
        @DisplayName("a server the bot refuses outright still answers the operator")
        void anUnreachableBotIsReported() {
            stopBot();

            assertTrue(run(staff, TARGET_NAME, "xray"));

            awaitTold(staff, "Failed to record offense");
            assertEquals(Collections.<String>emptyList(), platform.dispatchedCommands());
        }

        @Test
        @DisplayName("a server that refuses the punishment command says so — the infraction is filed")
        void aRefusedDispatchIsReported() {
            platform.failingDispatch(new IllegalStateException("no such command: warn"));

            assertTrue(run(staff, TARGET_NAME, "xray"));

            // The refusal is the last thing sent on this path, so everything before it has landed
            // by the time it has. Waiting on an earlier line would race the ones after it.
            awaitTold(staff, "the server refused to run");
            assertTrue(staff.wasTold("Offense recorded for " + TARGET_NAME),
                    "the infraction IS recorded and the punishment is NOT applied; only the person "
                            + "standing there can reconcile that");
            assertTrue(staff.wasTold("no such command"), staff.messageText().toString());
        }
    }

    // ── Tab completion ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("tab completion")
    class Completion {

        @Test
        @DisplayName("the first argument is online players, filtered by prefix and sorted")
        void playerNames() {
            assertEquals(Arrays.asList("Bystander", TARGET_NAME), complete(staff, ""));
            assertEquals(Arrays.asList(TARGET_NAME), complete(staff, "den"));
            assertEquals(Collections.<String>emptyList(), complete(staff, "zz"));
        }

        @Test
        @DisplayName("pressing tab with nothing typed offers everyone")
        void nothingTypedYet() {
            assertEquals(Arrays.asList("Bystander", TARGET_NAME), complete(staff));
        }

        @Test
        @DisplayName("the second argument is cached slugs, sorted and prefix-filtered")
        void offenseSlugs() {
            assertEquals(Arrays.asList("exploiting", "xray"), complete(staff, TARGET_NAME, ""));
            assertEquals(Arrays.asList("xray"), complete(staff, TARGET_NAME, "x"));
            assertEquals(Collections.<String>emptyList(), complete(staff, TARGET_NAME, "q"));
        }

        @Test
        @DisplayName("notes are free text, so nothing is suggested for them")
        void notesAreNotCompleted() {
            assertEquals(Collections.<String>emptyList(),
                    complete(staff, TARGET_NAME, "xray", "caught"));
        }

        @Test
        @DisplayName("a source without the permission is offered nothing at all")
        void completionIsGatedToo() {
            FakeCommandSource nobody = FakeCommandSource.player("Mallory");

            assertEquals(Collections.<String>emptyList(), complete(nobody, ""));
            assertEquals(Collections.<String>emptyList(), complete(nobody, TARGET_NAME, "x"));
        }

        @Test
        @DisplayName("completion never reaches the network — an unreachable bot changes nothing")
        void completionIsMemoryOnly() {
            stopBot();

            assertEquals(Arrays.asList("exploiting", "xray"), complete(staff, TARGET_NAME, ""));
        }
    }
}
