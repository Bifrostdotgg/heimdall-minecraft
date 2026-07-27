package com.heimdall.core.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ceiling arithmetic on its own.
 *
 * <p>{@link MirrorStoreTest} proves the rule produces the right <em>behaviour</em>; this pins the
 * expression itself, so a failure says whether the maths moved or the wiring did.
 */
class MirrorPolicyTest {

    private static final long HOUR = TimeUnit.HOURS.toMillis(1);

    /** A realistic epoch-millis value: the legacy-entry case only makes sense against a real clock. */
    private static final long VERIFIED = 1_700_000_000_000L;

    private static MirrorPolicy bounded(long maxExtensionHours) {
        return MirrorPolicy.builder().windowMinutes(60).maxExtensionHours(maxExtensionHours).build();
    }

    @Test
    @DisplayName("the effective expiry is the earlier of the entry's own and the ceiling")
    void effectiveExpiryTakesTheEarlier() {
        MirrorPolicy policy = bounded(6);

        assertEquals(VERIFIED + HOUR, policy.effectiveExpiry(VERIFIED + HOUR, VERIFIED),
                "an expiry inside the ceiling stands as written");
        assertEquals(VERIFIED + 6 * HOUR, policy.effectiveExpiry(VERIFIED + 48 * HOUR, VERIFIED),
                "an expiry beyond the ceiling is clamped to it, however it got there");
    }

    @Test
    @DisplayName("a long-ago verification puts the ceiling in the past")
    void staleVerificationCeilingIsInThePast() {
        MirrorPolicy policy = bounded(6);
        long verifiedLongAgo = VERIFIED - 48 * HOUR;

        assertTrue(policy.effectiveExpiry(VERIFIED + 24 * HOUR, verifiedLongAgo) < VERIFIED,
                "which is exactly why a future cacheExpiry alone must not be trusted");
    }

    @Test
    @DisplayName("lastVerified = 0, as a pre-upgrade entry deserializes, forces re-verification")
    void legacyEntryCeilingIsEpoch() {
        long effective = bounded(24).effectiveExpiry(VERIFIED + HOUR, 0L);

        assertEquals(24 * HOUR, effective, "the ceiling is 24 hours after the epoch");
        assertTrue(effective < VERIFIED,
                "which is 1970, so the entry is expired the moment it is read — that is the "
                        + "migration path for files written before lastVerified existed");
    }

    @Test
    void capClampsAProposedExpiry() {
        MirrorPolicy policy = bounded(1);

        assertEquals(VERIFIED + HOUR, policy.cap(VERIFIED + 3 * HOUR, VERIFIED));
        assertEquals(VERIFIED + 30 * 60 * 1000L, policy.cap(VERIFIED + 30 * 60 * 1000L, VERIFIED));
    }

    @Test
    @DisplayName("a zero ceiling disables both halves of the bound")
    void zeroDisablesTheBound() {
        MirrorPolicy unbounded = MirrorPolicy.builder().maxExtensionHours(0).build();

        assertFalse(unbounded.isExtensionBounded());
        assertEquals(VERIFIED + 999 * HOUR, unbounded.effectiveExpiry(VERIFIED + 999 * HOUR, 0L));
        assertEquals(VERIFIED + 999 * HOUR, unbounded.cap(VERIFIED + 999 * HOUR, 0L));
    }

    @Test
    @DisplayName("an enormous ceiling saturates instead of overflowing into the past")
    void hugeCeilingDoesNotOverflow() {
        // A hundred years in millis, plus a current timestamp, wraps a signed long negative — and
        // min(cacheExpiry, negative) then expires everything. The opposite of what someone asking
        // for a very long ceiling wanted, and silent.
        MirrorPolicy enormous = MirrorPolicy.builder()
                .maxExtensionMs(Long.MAX_VALUE)
                .build();

        assertTrue(enormous.effectiveExpiry(VERIFIED + HOUR, VERIFIED) > 0,
                "the ceiling must not wrap negative");
        assertEquals(VERIFIED + HOUR, enormous.effectiveExpiry(VERIFIED + HOUR, VERIFIED));
        assertEquals(Long.MAX_VALUE, enormous.cap(Long.MAX_VALUE, VERIFIED));
    }

    @Test
    void negativeSettingsAreClampedToZero() {
        MirrorPolicy policy = MirrorPolicy.builder()
                .windowMs(-1)
                .maxExtensionMs(-1)
                .saveDebounceMs(-1)
                .build();

        assertEquals(0L, policy.windowMs());
        assertEquals(0L, policy.maxExtensionMs());
        assertEquals(0L, policy.saveDebounceMs());
        assertFalse(policy.isExtensionBounded());
    }
}
