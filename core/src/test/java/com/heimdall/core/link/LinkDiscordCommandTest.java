package com.heimdall.core.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.http.ApiClient;
import com.heimdall.core.http.ApiSettings;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.RecordingCommands;
import com.heimdall.stubbot.PlayerFixture;
import com.heimdall.stubbot.StubBot;
import com.heimdall.stubbot.StubBotConfig;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code /linkdiscord}, its alias, its cooldown, and the already-linked answer.
 *
 * <p>Driven through {@link RecordingCommands}, which applies the permission gate the way both real
 * registrars do: a fake that skipped it would pass on a command that leaked staff functionality.
 *
 * <p>No module is involved. The command used to belong to the whitelist module and these tests ran
 * inside that module's harness; that the runtime registers it with no module at all is pinned in
 * {@code HeimdallRuntimeTest}.
 */
class LinkDiscordCommandTest {

    /** Demo fixture from {@code stub-bot/README.md}'s player table. */
    private static final String ALLOWED = "11111111-1111-1111-1111-111111111111";

    /** A stub bot, a client pointed at it, and the command registered into a recording registrar. */
    private static final class Harness implements AutoCloseable {

        final RecordingLogger logger = new RecordingLogger(true);
        final StubBot bot = StubBot.start(StubBotConfig.withDemoFixtures().bindHost("127.0.0.1").port(0));
        final HeimdallExecutors executors = new HeimdallExecutors(logger, 2);
        final RecordingCommands commands = new RecordingCommands();

        Harness(boolean configured) {
            ApiSettings settings = configured
                    ? ApiSettings.builder()
                            .baseUrl(bot.baseUrl())
                            .guildId(StubBotConfig.DEFAULT_GUILD_ID)
                            .apiKey(StubBotConfig.DEFAULT_API_KEY)
                            .serverId("survival")
                            .timeoutMs(4000)
                            .retries(1)
                            .retryDelayMs(20)
                            .build()
                    : ApiSettings.builder().build();
            HeimdallApi api = new HeimdallApi(new ApiClient(logger, settings, executors.io()));
            commands.register(new LinkDiscordCommand(logger, api).spec());
        }

        @Override
        public void close() {
            try {
                executors.shutdown(2000);
            } finally {
                bot.close();
            }
        }
    }

    /**
     * Waits for the asynchronous reply.
     *
     * <p>The handler must not block the command thread, so it fires the request and answers from the
     * future's completion. A test therefore has to wait for something the player will see rather than
     * for the call to return.
     */
    private static void awaitTold(FakeCommandSource source, String needle) {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (!source.wasTold(needle) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(source.wasTold(needle),
                "never told '" + needle + "'; was told " + source.messageText());
    }

    @Test
    @DisplayName("both /linkdiscord and /link are registered")
    void bothVerbsAnswer() {
        try (Harness h = new Harness(true)) {

            RecordingCommands commands = h.commands;
            assertTrue(commands.has("linkdiscord"));
            assertTrue(commands.has("link"), "/link is what players actually type");
        }
    }

    @Test
    @DisplayName("a player gets a six-digit code and how to use it")
    void playerGetsACode() {
        try (Harness h = new Harness(true)) {
            FakeCommandSource steve = FakeCommandSource
                    .player(UUID.fromString(ALLOWED), "Steve")
                    .grant(LinkDiscordCommand.PERMISSION);

            assertTrue(h.commands.run(steve, "linkdiscord"));

            awaitTold(steve, "Your Discord Link Code:");
            assertTrue(steve.wasTold("/confirm-code"),
                    "the code is useless without the Discord command: " + steve.messageText());
        }
    }

    @Test
    @DisplayName("already-linked is an answer, and names who, not an exception")
    void alreadyLinkedIsData() {
        try (Harness h = new Harness(true)) {
            // The stub answers alreadyLinked for a fixture carrying a linkedDiscordId.
            h.bot.fixtures().put(PlayerFixture
                    .of(ALLOWED, "Steve", com.heimdall.stubbot.Outcome.ALLOW)
                    .linkedTo("999888777666555444", "steve", "Steve"));
            FakeCommandSource steve = FakeCommandSource
                    .player(UUID.fromString(ALLOWED), "Steve")
                    .grant(LinkDiscordCommand.PERMISSION);

            h.commands.run(steve, "linkdiscord");

            // Departure D4: v2 threw a RuntimeException carrying this sentence, which discarded the
            // structured Discord fields and left the handler string-matching an exception message
            // to tell an ordinary outcome apart from a real failure.
            awaitTold(steve, "already linked");
            assertFalse(steve.wasTold("Your Discord Link Code:"),
                    "there is no code to give somebody who is already linked");
        }
    }

    @Test
    @DisplayName("a second attempt inside 30 seconds is refused, with the remaining time")
    void cooldownRefusesTheSecondAttempt() {
        try (Harness h = new Harness(true)) {
            FakeCommandSource steve = FakeCommandSource
                    .player(UUID.fromString(ALLOWED), "Steve")
                    .grant(LinkDiscordCommand.PERMISSION);

            h.commands.run(steve, "linkdiscord");
            awaitTold(steve, "Your Discord Link Code:");
            steve.clearMessages();

            h.commands.run(steve, "linkdiscord");

            assertTrue(steve.wasTold("Please wait"), steve.messageText().toString());
            assertTrue(steve.wasTold("seconds"), steve.messageText().toString());
            assertFalse(steve.wasTold("Your Discord Link Code:"),
                    "the cooldown has to actually stop the request");
        }
    }

    @Test
    @DisplayName("heimdall.bypass skips the cooldown: a permission works here, unlike at login")
    void bypassSkipsTheCooldown() {
        try (Harness h = new Harness(true)) {
            FakeCommandSource admin = FakeCommandSource
                    .player(UUID.fromString(ALLOWED), "Steve")
                    .grant(LinkDiscordCommand.PERMISSION)
                    .grant(LinkDiscordCommand.BYPASS_PERMISSION);

            h.commands.run(admin, "linkdiscord");
            awaitTold(admin, "Your Discord Link Code:");
            admin.clearMessages();

            h.commands.run(admin, "linkdiscord");

            // The login bypass cannot be a permission at all (permissions are not attached during
            // pre-login, #796 / MC-2), but this one is checked with the player very much online.
            awaitTold(admin, "Your Discord Link Code:");
            assertFalse(admin.wasTold("Please wait"), admin.messageText().toString());
        }
    }

    @Test
    @DisplayName("the console is told it has no account to link")
    void consoleIsRefused() {
        try (Harness h = new Harness(true)) {
            FakeCommandSource console = FakeCommandSource.console();

            h.commands.run(console, "linkdiscord");

            assertTrue(console.wasTold("Only a player"), console.messageText().toString());
        }
    }

    @Test
    @DisplayName("a player without the permission does not reach the handler at all")
    void permissionIsEnforcedByTheRegistrar() {
        try (Harness h = new Harness(true)) {
            FakeCommandSource nobody = FakeCommandSource
                    .player(UUID.fromString(ALLOWED), "Steve");

            assertFalse(h.commands.run(nobody, "linkdiscord"),
                    "the gate lives in the registrar on both real platforms");
            assertEquals(0, nobody.messageText().size());
        }
    }

    @Test
    @DisplayName("with no bot to ask, the player is told rather than left waiting")
    void unconfiguredServerSaysSo() {
        try (Harness h = new Harness(false)) {
            FakeCommandSource steve = FakeCommandSource
                    .player(UUID.fromString(ALLOWED), "Steve")
                    .grant(LinkDiscordCommand.PERMISSION);

            h.commands.run(steve, "linkdiscord");

            assertTrue(steve.wasTold("not connected to Discord yet"),
                    steve.messageText().toString());
        }
    }
}
