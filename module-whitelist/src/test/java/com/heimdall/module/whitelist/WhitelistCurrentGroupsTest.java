package com.heimdall.module.whitelist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.heimdall.core.json.Payload;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.core.testing.FakeLuckPerms;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code currentGroups} on {@code connection-attempt}: sent when known, left out when not.
 *
 * <p>The bot reads a missing key as "unknown" and makes no group decision, and an empty list as
 * "holds no groups", which it diffs against (issue #796 / MC-11) and which reverse role sync reads as
 * every mapped rank being gone. So the two must never be confused, and only a request that carried a
 * real list may tell role sync that the bot now knows the player's groups.
 */
class WhitelistCurrentGroupsTest {

    private static Payload settings() {
        return Payload.builder().put("prewarmEnabled", false).build();
    }

    private static JsonObject lastRequest(WhitelistHarness h) {
        JsonObject body = h.bot.lastConnectionAttemptRequest();
        assertNotNull(body, "no connection-attempt reached the stub");
        return body;
    }

    @Test
    @DisplayName("no LuckPerms: currentGroups is left out, and nothing is reported as delivered")
    void noLuckPermsOmitsTheKey(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(settings());

            Verdict verdict = h.login(WhitelistHarness.ALLOWED, "Steve");

            assertEquals(Verdict.Decision.ALLOW, verdict.decision());
            assertFalse(lastRequest(h).has("currentGroups"),
                    "absent LuckPerms is unknown, not an empty group list");
            assertTrue(h.roleSync.delivered().isEmpty(), h.roleSync.delivered().toString());
        }
    }

    @Test
    @DisplayName("a failed LuckPerms read: currentGroups is left out, and nothing is delivered")
    void failedReadOmitsTheKey(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.platform.withLuckPerms(
                    new FakeLuckPerms().failing(new IllegalStateException("storage down")));
            h.enableWith(settings());

            Verdict verdict = h.login(WhitelistHarness.ALLOWED, "Steve");

            assertEquals(Verdict.Decision.ALLOW, verdict.decision(),
                    "a group read failing must not change the login decision");
            assertFalse(lastRequest(h).has("currentGroups"));
            assertTrue(h.roleSync.delivered().isEmpty(), h.roleSync.delivered().toString());
        }
    }

    @Test
    @DisplayName("groups known: the real list is sent and reported as delivered")
    void knownGroupsAreSentAndDelivered(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            UUID steve = UUID.fromString(WhitelistHarness.ALLOWED);
            h.platform.withLuckPerms(new FakeLuckPerms().holding(steve, "default", "vip"));
            h.enableWith(settings());

            h.login(WhitelistHarness.ALLOWED, "Steve");

            JsonArray sent = lastRequest(h).getAsJsonArray("currentGroups");
            assertNotNull(sent);
            assertEquals(2, sent.size());
            assertEquals("default", sent.get(0).getAsString());
            assertEquals("vip", sent.get(1).getAsString());
            assertEquals(Collections.singletonList(steve + ":[default, vip]"),
                    h.roleSync.delivered());
        }
    }

    @Test
    @DisplayName("a denied login delivers nothing: the player is not joining")
    void deniedDeliversNothing(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            UUID alex = UUID.fromString(WhitelistHarness.DENIED);
            h.platform.withLuckPerms(new FakeLuckPerms().holding(alex, "default"));
            h.enableWith(settings());

            h.login(WhitelistHarness.DENIED, "Alex");

            assertTrue(lastRequest(h).has("currentGroups"));
            assertTrue(h.roleSync.delivered().isEmpty());
        }
    }
}
