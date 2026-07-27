package com.heimdall.module.whitelist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.core.testing.TestText;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The login gate, against the real bot contract.
 *
 * <p>All six connection-attempt outcomes, the order of the checks in front of them, and the two
 * regressions that are the reason this module was rewritten rather than ported: the mirror fast path
 * that stopped reporting (2.4.0), and the auth-code answer that must never be cached (#796 / MC-4).
 */
class WhitelistLoginOutcomesTest {

    /**
     * The base settings for these tests, with the pre-warm poll switched off.
     *
     * <p>The poll is correct and runs immediately on enable — see
     * {@link WhitelistMirrorSyncTest}, which is where it is exercised. It is off here so that "what
     * is in the mirror" means "what this login put there", which is the thing every assertion below
     * is actually about.
     */
    private static Payload settings() {
        return Payload.builder().put("prewarmEnabled", false).build();
    }

    private static String reasonOf(Verdict verdict) {
        return TestText.plain(verdict.reason());
    }

    @Nested
    @DisplayName("the six outcomes")
    class SixOutcomes {

        @Test
        @DisplayName("allow — admitted, and remembered in the mirror")
        void allow(@TempDir Path dir) {
            try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
                h.enableWith(settings());

                Verdict verdict = h.login(WhitelistHarness.ALLOWED, "Steve");

                assertEquals(Verdict.Decision.ALLOW, verdict.decision());
                assertTrue(h.module.mirrorStats().startsWith("1 entries"),
                        "a confirmed player belongs in the mirror: " + h.module.mirrorStats());
            }
        }

        @Test
        @DisplayName("deny — refused, with the bot's own message")
        void deny(@TempDir Path dir) {
            try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
                h.enableWith(settings());

                Verdict verdict = h.login(WhitelistHarness.DENIED, "Alex");

                assertTrue(verdict.isDeny());
                assertFalse(reasonOf(verdict).isEmpty(),
                        "the bot renders the message; an empty kick screen is a bug");
                assertTrue(h.module.mirrorStats().startsWith("0 entries"));
            }
        }

        @Test
        @DisplayName("pending_auth — refused with the code, and NOT cached")
        void pendingAuthIsNeverCached(@TempDir Path dir) {
            try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
                h.enableWith(settings());

                Verdict verdict = h.login(WhitelistHarness.PENDING_AUTH, "Coder");

                assertTrue(verdict.isDeny(), "the player has to go and link before joining");
                assertTrue(reasonOf(verdict).contains("135790"),
                        "the code is the whole point of the kick: " + reasonOf(verdict));
                assertTrue(h.module.mirrorStats().startsWith("0 entries"),
                        "issue #796 / MC-4: this answer is whitelisted:true, so a naive "
                                + "'cache if whitelisted' admits them next time without ever "
                                + "showing a code — and they can then never link");
            }
        }

        @Test
        @DisplayName("revoked — refused, and said so distinctly in the log")
        void revoked(@TempDir Path dir) {
            try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
                h.enableWith(settings());

                Verdict verdict = h.login(WhitelistHarness.REVOKED, "Griefer");

                assertTrue(verdict.isDeny());
                assertTrue(h.logger.logged(com.heimdall.core.log.LogLevel.INFO, "revoked"),
                        "'you were whitelisted and no longer are' is a different support "
                                + "conversation from 'you never were' (departure D6): "
                                + h.logger.records());
            }
        }

        @Test
        @DisplayName("pending_approval with a queue position — refused, position carried")
        void pendingApprovalWithPosition(@TempDir Path dir) {
            try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
                h.enableWith(settings());

                Verdict verdict = h.login(WhitelistHarness.QUEUED, "Queued");

                assertTrue(verdict.isDeny());
                assertTrue(h.logger.logged(com.heimdall.core.log.LogLevel.INFO, "position 3"),
                        h.logger.records().toString());
            }
        }

        @Test
        @DisplayName("pending_approval on the SCHEDULED branch — no position invented")
        void pendingApprovalScheduled(@TempDir Path dir) {
            try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
                h.enableWith(settings());

                Verdict verdict = h.login(WhitelistHarness.SCHEDULED, "ScheduledSam");

                assertTrue(verdict.isDeny());
                // Departure D1: the bot omits queuePosition entirely on this branch, and v2's
                // primitive int could not tell that from "position zero" — so it showed these
                // players a queue position that did not exist.
                assertFalse(h.logger.logged(com.heimdall.core.log.LogLevel.INFO, "position"),
                        "no position exists on the scheduled branch: " + h.logger.records());
            }
        }

        @Test
        @DisplayName("existing_link — refused with the offer, and NOT cached")
        void existingLink(@TempDir Path dir) {
            try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
                h.enableWith(settings());

                Verdict verdict = h.login(WhitelistHarness.EXISTING_LINK, "Linker");

                // The outcome most easily got wrong: the bot answers whitelisted:true AND supplies
                // an auth code. v2 refused it with the code in the kick message, because the player
                // is being told how to claim an account that already exists and cannot read that
                // while joining. Same here.
                assertTrue(verdict.isDeny());
                assertTrue(reasonOf(verdict).contains("246800"), reasonOf(verdict));
                assertTrue(h.module.mirrorStats().startsWith("0 entries"),
                        "whitelisted:true, and still must not be cached");
            }
        }
    }

    @Nested
    @DisplayName("the mirror fast path")
    class MirrorFastPath {

        @Test
        @DisplayName("REGRESSION 2.4.0: a mirror hit still fires the connection report")
        void mirrorHitStillReportsAsynchronously(@TempDir Path dir) throws Exception {
            try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
                h.enableWith(settings());

                // First login populates the mirror and applies role sync through the blocking path.
                assertEquals(Verdict.Decision.ALLOW,
                        h.login(WhitelistHarness.ALLOWED, "Steve").decision());
                h.roleSync.clear();

                // Second login is a mirror hit — the common path on a pre-warmed server. v2 skipped
                // the API call here, so role sync, the bot's connection history and the dashboard's
                // join feed all silently stopped happening for everybody who was already cached.
                assertEquals(Verdict.Decision.ALLOW,
                        h.login(WhitelistHarness.ALLOWED, "Steve").decision());

                long deadline = System.currentTimeMillis() + 10_000L;
                while (h.roleSync.applied().isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
                assertEquals(1, h.roleSync.applied().size(),
                        "the report must ride a mirror hit — this is the 2.4.0 outage: "
                                + h.roleSync.applied());
                assertTrue(h.roleSync.applied().get(0).startsWith("Steve:enabled"),
                        h.roleSync.applied().toString());
            }
        }

        @Test
        @DisplayName("a mirror hit does not block on the report")
        void mirrorHitIsNotBlocking(@TempDir Path dir) {
            try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
                h.enableWith(settings());
                h.login(WhitelistHarness.ALLOWED, "Steve");

                // The stub is gone, so the background report cannot possibly succeed. The login
                // must be unaffected: the mirror already answered.
                h.breakTheBot();

                assertEquals(Verdict.Decision.ALLOW,
                        h.login(WhitelistHarness.ALLOWED, "Steve").decision(),
                        "a failed background report must never reach the player");
            }
        }
    }

    @Nested
    @DisplayName("the checks in front of the bot")
    class Guards {

        @Test
        @DisplayName("a bypassed UUID abstains, and never asks the bot")
        void bypassAbstains(@TempDir Path dir) {
            try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
                h.enableWith(Payload.builder()
                        .put("prewarmEnabled", false)
                        // Deliberately a different case and with surrounding space, because
                        // BypassList trims and compares case-insensitively and an operator pastes
                        // whatever the dashboard showed them.
                        .putStrings("bypassUuids",
                                Arrays.asList(" " + WhitelistHarness.DENIED.toUpperCase() + " "))
                        .build());

                Verdict verdict = h.login(WhitelistHarness.DENIED, "Alex");

                // The PIPELINE answers ALLOW, because allow is its default decision when every
                // interceptor abstains. What matters is that this interceptor abstained rather than
                // allowing: an allow would be a verdict, and would silently veto the punishments
                // gate its priority leaves room for (departure D32). The log line is the only place
                // the difference is observable from outside the pipeline.
                assertEquals(Verdict.Decision.ALLOW, verdict.decision());
                assertTrue(h.logger.logged(
                        com.heimdall.core.log.LogLevel.DEBUG, "is on the bypass list"),
                        "a bypassed player must not have been asked about: " + h.logger.records());
                assertTrue(h.module.mirrorStats().startsWith("0 entries"),
                        "and must not be mirrored either");
            }
        }

        @Test
        @DisplayName("a disabled module abstains rather than gating")
        void disabledAbstains(@TempDir Path dir) {
            try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
                h.enableWith(settings());
                h.disableModule();

                // Nothing is registered any more, so the pipeline's own default decides.
                assertEquals(0, h.loginPipeline.size());
                assertEquals(Verdict.Decision.ALLOW,
                        h.login(WhitelistHarness.DENIED, "Alex").decision(),
                        "a switched-off whitelist must not keep refusing people");
            }
        }

        @Test
        @DisplayName("a backend enforces by default — v2 parity")
        void backendEnforcesByDefault(@TempDir Path dir) {
            try (WhitelistHarness h = WhitelistHarness.withRole(dir, ServerRole.ENFORCER)) {
                h.enableWith(settings());

                assertTrue(h.login(WhitelistHarness.DENIED, "Alex").isDeny(),
                        "v2 had no role concept and enforced wherever it was installed; a backend "
                                + "whose proxy lacks the plugin needs this");
            }
        }

        @Test
        @DisplayName("a backend can be told the gatekeeper owns the decision")
        void backendCanOptOut(@TempDir Path dir) {
            try (WhitelistHarness h = WhitelistHarness.withRole(dir, ServerRole.ENFORCER)) {
                h.enableWith(Payload.builder()
                        .put("prewarmEnabled", false)
                        .put("enforceOnBackend", false)
                        .build());

                assertEquals(Verdict.Decision.ALLOW,
                        h.login(WhitelistHarness.DENIED, "Alex").decision());
                assertTrue(h.logger.logged(
                        com.heimdall.core.log.LogLevel.DEBUG, "the gatekeeper owns the decision"),
                        h.logger.records().toString());
            }
        }

        @Test
        @DisplayName("a gatekeeper ignores enforceOnBackend entirely")
        void gatekeeperAlwaysEnforces(@TempDir Path dir) {
            try (WhitelistHarness h = WhitelistHarness.withRole(dir, ServerRole.GATEKEEPER)) {
                h.enableWith(Payload.builder()
                        .put("prewarmEnabled", false)
                        .put("enforceOnBackend", false)
                        .build());

                assertTrue(h.login(WhitelistHarness.DENIED, "Alex").isDeny(),
                        "the setting is about backends; a proxy owning the decision must still "
                                + "make it");
            }
        }
    }

    @Nested
    @DisplayName("when the bot cannot be reached")
    class FallbackModes {

        private WhitelistHarness broken(Path dir, Payload extra) {
            WhitelistHarness h = WhitelistHarness.standalone(dir);
            h.enableWith(extra.toBuilder().put("prewarmEnabled", false).build());
            // Populate the mirror while the bot is alive, then take it away — which is the shape of
            // a redeploy, and the case the whole pre-warm design exists for.
            h.login(WhitelistHarness.ALLOWED, "Steve");
            h.breakTheBot();
            return h;
        }

        @Test
        @DisplayName("allow — everybody gets in")
        void failOpen(@TempDir Path dir) {
            try (WhitelistHarness h = broken(dir, Payload.builder()
                    .put("apiFallbackMode", "allow").build())) {
                assertEquals(Verdict.Decision.ALLOW,
                        h.login(WhitelistHarness.DENIED, "Alex").decision());
            }
        }

        @Test
        @DisplayName("deny — nobody does, not even a mirrored player")
        void failClosed(@TempDir Path dir) {
            try (WhitelistHarness h = broken(dir, Payload.builder()
                    .put("apiFallbackMode", "deny").build())) {
                assertTrue(h.login(WhitelistHarness.DENIED, "Alex").isDeny());
                // Steve IS in the mirror, and a mirror hit is decided before the bot is ever asked,
                // so fail-closed never sees him. Worth pinning: reading "deny" as "deny everybody"
                // would be a change that locks out exactly the players pre-warming protects.
                assertEquals(Verdict.Decision.ALLOW,
                        h.login(WhitelistHarness.ALLOWED, "Steve").decision(),
                        "the fallback only applies when the bot was actually asked");
            }
        }

        @Test
        @DisplayName("whitelist-only is the default, and the mirror decides")
        void whitelistOnlyIsTheDefault(@TempDir Path dir) {
            // No apiFallbackMode set at all. v2's shipped config.yml carried whitelist-only while
            // its code default was deny; every real installation ran on the file's value, so that
            // is the parity one.
            try (WhitelistHarness h = broken(dir, Payload.empty())) {
                assertEquals(Verdict.Decision.ALLOW,
                        h.login(WhitelistHarness.ALLOWED, "Steve").decision(),
                        "a mirrored player must survive a bot redeploy — that is the entire point "
                                + "of pre-warming the mirror");
                assertTrue(h.login(WhitelistHarness.DENIED, "Alex").isDeny());
            }
        }

        @Test
        @DisplayName("an unrecognised mode falls back to whitelist-only, not to either extreme")
        void unknownModeIsTheMiddle(@TempDir Path dir) {
            try (WhitelistHarness h = broken(dir, Payload.builder()
                    .put("apiFallbackMode", "wide-open-please").build())) {
                assertTrue(h.login(WhitelistHarness.DENIED, "Alex").isDeny(),
                        "a typo must not silently disable the whitelist");
                assertEquals(Verdict.Decision.ALLOW,
                        h.login(WhitelistHarness.ALLOWED, "Steve").decision(),
                        "nor silently lock the server");
            }
        }

        @Test
        @DisplayName("no guild yet runs the fallback rather than refusing outright")
        void discoveringGuildUsesTheFallback(@TempDir Path dir) {
            try (WhitelistHarness h = WhitelistHarness.unconfigured(dir)) {
                h.enableWith(Payload.empty());

                Verdict verdict = h.login(WhitelistHarness.ALLOWED, "Steve");

                // v2 refused outright here. Running the configured fallback is better: with the
                // default, a server restarting during a bot outage still serves its mirror, and a
                // server that has genuinely never been set up has an empty one and refuses anyway.
                assertTrue(verdict.isDeny(), "an empty mirror refuses, which is correct");
                assertTrue(h.logger.logged(
                        com.heimdall.core.log.LogLevel.WARN, "has not resolved its guild"),
                        h.logger.records().toString());
            }
        }
    }
}
