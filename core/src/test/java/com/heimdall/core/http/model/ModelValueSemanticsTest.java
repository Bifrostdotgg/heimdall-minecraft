package com.heimdall.core.http.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every response model compares by value.
 *
 * <p>Not decoration: change detection is comparison. Deciding whether a re-fetched offense-type list
 * actually differs, or whether a player's role-sync directive has moved since the last join, is a
 * value question, and identity comparison answers "it changed" every single time — which turns a
 * cheap poll into a permanent rewrite of whatever it feeds.
 */
class ModelValueSemanticsTest {

    private static OffenseType offenseType(String displayName) {
        return OffenseType.builder()
                .typeId("cheating")
                .displayName(displayName)
                .description("Client modifications")
                .offenses(Arrays.asList("xray", "exploiting"))
                .enabled(true)
                .build();
    }

    @Test
    void offenseTypesCompareByValue() {
        assertEquals(offenseType("Cheating"), offenseType("Cheating"));
        assertEquals(offenseType("Cheating").hashCode(), offenseType("Cheating").hashCode());
        assertNotEquals(offenseType("Cheating"), offenseType("Cheating (renamed)"));
    }

    @Test
    @DisplayName("a re-fetched list equals the previous one, so nothing downstream is rewritten")
    void anUnchangedListIsEqual() {
        assertEquals(
                Arrays.asList(offenseType("Cheating"), offenseType("Griefing")),
                Arrays.asList(offenseType("Cheating"), offenseType("Griefing")));
    }

    @Test
    void connectionResultsCompareByValue() {
        ConnectionAttemptResult allowed = ConnectionAttemptResult.builder()
                .whitelisted(true)
                .action(ConnectionAction.ALLOW)
                .roleSync(RoleSyncDirective.enabled(
                        Collections.singletonList("vip"), Collections.singletonList("vip")))
                .build();
        ConnectionAttemptResult same = ConnectionAttemptResult.builder()
                .whitelisted(true)
                .action(ConnectionAction.ALLOW)
                .roleSync(RoleSyncDirective.enabled(
                        Collections.singletonList("vip"), Collections.singletonList("vip")))
                .build();

        assertEquals(allowed, same);
        assertEquals(allowed.hashCode(), same.hashCode());

        ConnectionAttemptResult withoutGroups = ConnectionAttemptResult.builder()
                .whitelisted(true)
                .action(ConnectionAction.ALLOW)
                .build();
        assertNotEquals(allowed, withoutGroups,
                "an absent roleSync directive is not the same verdict as an enabled one");
    }

    @Test
    @DisplayName("an absent queue position is not equal to position zero")
    void nullQueuePositionIsDistinct() {
        ConnectionAttemptResult scheduled = ConnectionAttemptResult.builder()
                .action(ConnectionAction.PENDING_APPROVAL)
                .build();
        ConnectionAttemptResult queued = ConnectionAttemptResult.builder()
                .action(ConnectionAction.PENDING_APPROVAL)
                .queuePosition(Integer.valueOf(0))
                .build();

        assertNotEquals(scheduled, queued,
                "the whole reason queuePosition is a nullable Integer");
    }

    @Test
    void whitelistEntriesAndResultsCompareByValue() {
        WhitelistSyncEntry steve = new WhitelistSyncEntry("u1", "Steve");
        assertEquals(steve, new WhitelistSyncEntry("u1", "Steve"));
        assertNotEquals(steve, new WhitelistSyncEntry("u1", null));

        WhitelistSyncResult first = WhitelistSyncResult.modified()
                .etag("\"abc\"")
                .hash("abc")
                .count(1)
                .generatedAt("2026-01-01T00:00:00.000Z")
                .players(Collections.singletonList(steve))
                .build();
        WhitelistSyncResult second = WhitelistSyncResult.modified()
                .etag("\"abc\"")
                .hash("abc")
                .count(1)
                .generatedAt("2026-01-01T00:00:00.000Z")
                .players(Collections.singletonList(new WhitelistSyncEntry("u1", "Steve")))
                .build();

        assertEquals(first, second);
        assertNotEquals(first, WhitelistSyncResult.notModified("\"abc\""),
                "a 304 is not the same result as the dump it declined to re-send");
    }

    @Test
    void linkCodesAndReleasesCompareByValue() {
        assertEquals(LinkCodeResult.code("135790"), LinkCodeResult.code("135790"));
        assertNotEquals(LinkCodeResult.code("135790"), LinkCodeResult.linkedTo().discordId("42").build());

        assertEquals(
                PluginRelease.builder().version("v3.0.0").downloadUrl("https://x/a.jar").build(),
                PluginRelease.builder().version("v3.0.0").downloadUrl("https://x/a.jar").build());
        assertNotEquals(
                PluginRelease.builder().version("v3.0.0").build(),
                PluginRelease.builder().version("v3.0.1").build());
    }

    @Test
    void offenseReportsAndResultsCompareByValue() {
        OffenseReport report = OffenseReport.builder("u1", "Steve", "xray").build();
        assertEquals(report, OffenseReport.builder("u1", "Steve", "XRAY").build());
        assertNotEquals(report, OffenseReport.builder("u1", "Steve", "exploiting").build());

        OffenseResult warned = OffenseResult.builder().action("warn").tierApplied(1).build();
        assertEquals(warned, OffenseResult.builder().action("warn").tierApplied(1).build());
        assertNotEquals(warned, OffenseResult.builder().action("warn").tierApplied(2).build());
    }

    @Test
    @DisplayName("toString says something useful without leaking the whole object graph")
    void toStringIsInformative() {
        assertTrue(offenseType("Cheating").toString().contains("cheating"));
        assertTrue(LinkCodeResult.code("135790").toString().contains("135790"));
        assertTrue(WhitelistSyncResult.notModified("\"abc\"").toString().contains("notModified"));
    }
}
