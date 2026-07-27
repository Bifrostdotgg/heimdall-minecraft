package com.heimdall.stubbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Golden-vector tests for the whitelist-sync ETag.
 *
 * <p>Same provenance rule as {@link HmacTest}: the expected digests were computed independently in
 * PowerShell and in Node, not by this code. The algorithm itself is transcribed from
 * {@code computeHash} in the bot's {@code api/whitelistSync.ts} — SHA-1 over the sorted UUIDs, each
 * followed by a newline.
 */
class WhitelistEtagTest {

    private static final String A = "00000000-0000-0000-0000-000000000001";
    private static final String B = "11111111-2222-3333-4444-555555555555";
    private static final String C = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @Test
    void emptyWhitelistHashesTheEmptyString() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", FixtureStore.etag(List.of()));
    }

    @Test
    void singleEntry() {
        assertEquals("774864e2e86bc1d01827984372a1e8453759cee8", FixtureStore.etag(List.of(B)));
    }

    @Test
    void threeEntries() {
        assertEquals("bc9e075e23532f87033c1ec6cf3355122829cbe3", FixtureStore.etag(List.of(B, C, A)));
    }

    @Test
    @DisplayName("the hash is order-independent — which is the whole point of sorting first")
    void orderIndependent() {
        assertEquals(FixtureStore.etag(List.of(A, B, C)), FixtureStore.etag(List.of(C, A, B)));
        assertEquals(FixtureStore.etag(List.of(A, B, C)), FixtureStore.etag(List.of(B, C, A)));
    }

    @Test
    @DisplayName("membership changes DO change the hash, so a revocation is not silently cached")
    void membershipChangesTheHash() {
        String before = FixtureStore.etag(List.of(A, B, C));
        assertNotEquals(before, FixtureStore.etag(List.of(A, B)), "removing a player");
        assertNotEquals(before, FixtureStore.etag(List.of(A, B, C, "ffffffff-ffff-ffff-ffff-ffffffffffff")),
                "adding a player");
    }

    @Test
    @DisplayName("the store derives the whitelist from the outcomes, not a separate flag")
    void storeDerivesWhitelistFromOutcomes() {
        FixtureStore store = new FixtureStore(Outcome.DENY);
        store.put(PlayerFixture.of(A, "Allowed", Outcome.ALLOW));
        store.put(PlayerFixture.of(B, "Legacy", Outcome.EXISTING_LINK));
        store.put(PlayerFixture.of(C, "Revoked", Outcome.REVOKED));

        assertEquals(FixtureStore.etag(List.of(A, B)), store.currentEtag(),
                "EXISTING_LINK is whitelisted (the bot answers whitelisted:true); REVOKED is not");

        store.put(PlayerFixture.of(A, "Allowed", Outcome.REVOKED));
        assertEquals(FixtureStore.etag(List.of(B)), store.currentEtag(),
                "revoking a player has to move the ETag or the plugin's pre-warm cache goes stale");
    }
}
