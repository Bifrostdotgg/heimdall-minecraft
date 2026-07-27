package com.heimdall.module.whitelist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.json.Payload;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.stubbot.Outcome;
import com.heimdall.stubbot.PlayerFixture;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pre-warm poll, the ETag, and the join/quit windows.
 *
 * <p>These are the resilience half of the module: everything here is about what happens when the bot
 * is <em>not</em> answering, and whether the local copy is good enough to carry a server through it.
 */
class WhitelistMirrorSyncTest {

    @Test
    @DisplayName("the pre-warm runs on enable, so a fresh boot is protected immediately")
    void prewarmRunsImmediately(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(Payload.empty());

            // The demo fixtures make ALLOWED and EXISTING_LINK whitelisted and nobody else.
            String stats = h.awaitMirrorEntries(2);

            assertTrue(stats.startsWith("2 entries"),
                    "waiting out the first interval is five minutes in which a bot outage refuses "
                            + "everybody the mirror has forgotten: " + stats);
        }
    }

    @Test
    @DisplayName("a pre-warmed player survives the bot vanishing entirely")
    void prewarmCarriesAnOutage(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(Payload.empty());
            h.awaitMirrorEntries(2);
            // Never logged in, never asked about — only ever mirrored by the poll. This is the
            // player v2's per-connection cache did nothing for.
            h.breakTheBot();

            assertEquals(Verdict.Decision.ALLOW,
                    h.login(WhitelistHarness.ALLOWED, "Steve").decision(),
                    "the entire point of pre-warming: a redeploy is invisible to EVERY whitelisted "
                            + "player, not just the ones who happened to connect recently");
        }
    }

    @Test
    @DisplayName("a second sync against an unchanged whitelist is a 304, and keeps the entries")
    void etagShortCircuitsAnUnchangedWhitelist(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(Payload.empty());
            assertNotNull(h.awaitMirrorEntries(2));
            assertFalse(h.module.mirrorStats().contains("etag=none"),
                    "the ETag is stored so a restart does not pull a dump it already has "
                            + "(departure D11): " + h.module.mirrorStats());
            h.logger.clear();

            h.module.syncNow();

            assertTrue(h.logger.logged(LogLevel.DEBUG, "whitelist unchanged"),
                    "an unchanged whitelist must cost a 304 with no body: " + h.logger.records());
            assertTrue(h.module.mirrorStats().startsWith("2 entries"));
        }
    }

    @Test
    @DisplayName("a sync reconciles both ways — new rows in, removed rows pruned")
    void reconcileAddsAndPrunes(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(Payload.empty());
            assertTrue(h.awaitMirrorEntries(2).startsWith("2 entries"));

            // Whitelist membership is derived from the outcome, so flipping Alex to allow puts her
            // on it and flipping Steve to deny takes him off — one change, both directions.
            h.bot.fixtures().put(PlayerFixture.of(WhitelistHarness.DENIED, "Alex", Outcome.ALLOW));
            h.bot.fixtures().put(PlayerFixture.of(WhitelistHarness.ALLOWED, "Steve", Outcome.DENY));

            h.module.syncNow();

            assertTrue(h.module.mirrorStats().startsWith("2 entries"), h.module.mirrorStats());
            assertEquals(Verdict.Decision.ALLOW, h.login(WhitelistHarness.DENIED, "Alex").decision());
            // Steve is off the whitelist and out of the mirror, so this reaches the bot and is
            // refused — which is how a revocation actually propagates.
            assertTrue(h.login(WhitelistHarness.ALLOWED, "Steve").isDeny());
        }
    }

    @Test
    @DisplayName("a failed sync leaves the mirror exactly as it was")
    void aFailedSyncNeverClears(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(Payload.empty());
            String before = h.awaitMirrorEntries(2);
            h.breakTheBot();
            h.logger.clear();

            h.module.syncNow();

            assertEquals(before, h.module.mirrorStats(),
                    "the moment the bot is unreachable is precisely the moment the mirror's "
                            + "contents are load-bearing; wiping it there would be the outage");
            assertTrue(h.logger.logged(LogLevel.WARN, "pre-warm failed"), h.logger.records().toString());
        }
    }

    @Test
    @DisplayName("prewarmEnabled: false really stops the poll")
    void prewarmCanBeSwitchedOff(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(Payload.builder().put("prewarmEnabled", false).build());

            assertTrue(h.module.mirrorStats().startsWith("0 entries"), h.module.mirrorStats());
        }
    }

    @Test
    @DisplayName("a join slides the entry forward, and a quit slides it further")
    void sessionWindowsExtendTheEntry(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(Payload.empty());
            h.awaitMirrorEntries(2);
            FakePlayer steve = new FakePlayer(UUID.fromString(WhitelistHarness.ALLOWED), "Steve");

            // The listeners are registered through the context, so this is the real path a platform
            // adapter drives. Both must find the entry the pre-warm put there.
            h.sessions.join(steve, System.currentTimeMillis());
            h.sessions.quit(steve, System.currentTimeMillis());

            assertFalse(h.logger.logged(LogLevel.DEBUG, "no mirror entry to extend for Steve"),
                    "a mirrored player's window must actually be extendable: " + h.logger.records());
            assertTrue(h.module.mirrorStats().startsWith("2 entries"));
        }
    }

    @Test
    @DisplayName("a session event for somebody the mirror never held is not an error")
    void anUnmirroredSessionIsQuiet(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(Payload.empty());
            h.awaitMirrorEntries(2);
            FakePlayer bypassed = new FakePlayer(UUID.fromString(WhitelistHarness.DENIED), "Alex");

            h.sessions.join(bypassed, System.currentTimeMillis());

            // A bypassed player, or one admitted while the whitelist was off, was never mirrored.
            // Ordinary, so debug — a warning here would fire on every join on a server that uses
            // the bypass list.
            assertTrue(h.logger.at(LogLevel.WARN).isEmpty(),
                    "this is the ordinary case, not a fault: " + h.logger.at(LogLevel.WARN));
        }
    }

    @Test
    @DisplayName("session listeners stop when the module is switched off")
    void sessionListenersAreUnwound(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(Payload.empty());
            h.awaitMirrorEntries(2);
            assertEquals(1, h.sessions.joinListenerCount());
            assertEquals(1, h.sessions.quitListenerCount());

            h.disableModule();

            assertEquals(0, h.sessions.joinListenerCount(),
                    "a disabled module still extending cache windows is the same class of bug as "
                            + "one still gating logins");
            assertEquals(0, h.sessions.quitListenerCount());
        }
    }
}
