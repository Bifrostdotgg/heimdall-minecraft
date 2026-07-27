package com.heimdall.module.whitelist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.json.Payload;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.RecordingCommands;
import com.heimdall.stubbot.PlayerFixture;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /linkdiscord}, its alias, its cooldown, and the already-linked answer.
 *
 * <p>Driven through {@link RecordingCommands}, which applies the permission gate the way both real
 * registrars do — a fake that skipped it would pass on a command that leaked staff functionality.
 */
class LinkDiscordCommandTest {

    private static Payload settings() {
        return Payload.builder().put("prewarmEnabled", false).build();
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
    void bothVerbsAnswer(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(settings());

            RecordingCommands commands = h.platform.commandRegistry();
            assertTrue(commands.has("linkdiscord"));
            assertTrue(commands.has("link"), "/link is what players actually type");
        }
    }

    @Test
    @DisplayName("a player gets a six-digit code and how to use it")
    void playerGetsACode(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(settings());
            FakeCommandSource steve = FakeCommandSource
                    .player(UUID.fromString(WhitelistHarness.ALLOWED), "Steve")
                    .grant(LinkDiscordCommand.PERMISSION);

            assertTrue(h.platform.commandRegistry().run(steve, "linkdiscord"));

            awaitTold(steve, "Your Discord Link Code:");
            assertTrue(steve.wasTold("/confirm-code"),
                    "the code is useless without the Discord command: " + steve.messageText());
        }
    }

    @Test
    @DisplayName("already-linked is an answer, and names who — not an exception")
    void alreadyLinkedIsData(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(settings());
            // The stub answers alreadyLinked for a fixture carrying a linkedDiscordId.
            h.bot.fixtures().put(PlayerFixture
                    .of(WhitelistHarness.ALLOWED, "Steve", com.heimdall.stubbot.Outcome.ALLOW)
                    .linkedTo("999888777666555444", "steve", "Steve"));
            FakeCommandSource steve = FakeCommandSource
                    .player(UUID.fromString(WhitelistHarness.ALLOWED), "Steve")
                    .grant(LinkDiscordCommand.PERMISSION);

            h.platform.commandRegistry().run(steve, "linkdiscord");

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
    void cooldownRefusesTheSecondAttempt(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(settings());
            FakeCommandSource steve = FakeCommandSource
                    .player(UUID.fromString(WhitelistHarness.ALLOWED), "Steve")
                    .grant(LinkDiscordCommand.PERMISSION);

            h.platform.commandRegistry().run(steve, "linkdiscord");
            awaitTold(steve, "Your Discord Link Code:");
            steve.clearMessages();

            h.platform.commandRegistry().run(steve, "linkdiscord");

            assertTrue(steve.wasTold("Please wait"), steve.messageText().toString());
            assertTrue(steve.wasTold("seconds"), steve.messageText().toString());
            assertFalse(steve.wasTold("Your Discord Link Code:"),
                    "the cooldown has to actually stop the request");
        }
    }

    @Test
    @DisplayName("heimdall.bypass skips the cooldown — a permission works here, unlike at login")
    void bypassSkipsTheCooldown(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(settings());
            FakeCommandSource admin = FakeCommandSource
                    .player(UUID.fromString(WhitelistHarness.ALLOWED), "Steve")
                    .grant(LinkDiscordCommand.PERMISSION)
                    .grant(LinkDiscordCommand.BYPASS_PERMISSION);

            h.platform.commandRegistry().run(admin, "linkdiscord");
            awaitTold(admin, "Your Discord Link Code:");
            admin.clearMessages();

            h.platform.commandRegistry().run(admin, "linkdiscord");

            // The login bypass cannot be a permission at all — permissions are not attached during
            // pre-login (#796 / MC-2) — but this one is checked with the player very much online.
            awaitTold(admin, "Your Discord Link Code:");
            assertFalse(admin.wasTold("Please wait"), admin.messageText().toString());
        }
    }

    @Test
    @DisplayName("the console is told it has no account to link")
    void consoleIsRefused(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(settings());
            FakeCommandSource console = FakeCommandSource.console();

            h.platform.commandRegistry().run(console, "linkdiscord");

            assertTrue(console.wasTold("Only a player"), console.messageText().toString());
        }
    }

    @Test
    @DisplayName("a player without the permission does not reach the handler at all")
    void permissionIsEnforcedByTheRegistrar(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(settings());
            FakeCommandSource nobody = FakeCommandSource
                    .player(UUID.fromString(WhitelistHarness.ALLOWED), "Steve");

            assertFalse(h.platform.commandRegistry().run(nobody, "linkdiscord"),
                    "the gate lives in the registrar on both real platforms");
            assertEquals(0, nobody.messageText().size());
        }
    }

    @Test
    @DisplayName("with no bot to ask, the player is told rather than left waiting")
    void unconfiguredServerSaysSo(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.unconfigured(dir)) {
            h.enableWith(settings());
            FakeCommandSource steve = FakeCommandSource
                    .player(UUID.fromString(WhitelistHarness.ALLOWED), "Steve")
                    .grant(LinkDiscordCommand.PERMISSION);

            h.platform.commandRegistry().run(steve, "linkdiscord");

            assertTrue(steve.wasTold("not connected to Discord yet"),
                    steve.messageText().toString());
        }
    }

    @Test
    @DisplayName("switching the module off takes the command away")
    void disableUnregistersTheCommand(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(settings());
            assertTrue(h.platform.commandRegistry().has("linkdiscord"));

            h.disableModule();

            assertFalse(h.platform.commandRegistry().has("linkdiscord"),
                    "v2 had no way to take a command back, so a switched-off feature still "
                            + "answered — departure D30");
            assertFalse(h.platform.commandRegistry().has("link"));
        }
    }
}
