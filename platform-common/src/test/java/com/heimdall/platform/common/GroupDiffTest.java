package com.heimdall.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The half of role sync that fails quietly when it is wrong.
 *
 * <p>Every case here is one somebody has actually hit or one that would delete a group a player
 * paid for. The tests are written against lists rather than against a fake LuckPerms because the
 * logic is a function of three lists — faking {@code User}, {@code Group}, {@code NodeMap} and two
 * managers would test the mock's fidelity, not the rule.
 */
class GroupDiffTest {

    private static List<String> list(String... values) {
        return Arrays.asList(values);
    }

    @Test
    @DisplayName("an empty managed list changes nothing at all")
    void emptyManagedIsInert() {
        GroupDiff diff = GroupDiff.compute(
                list("default", "vip", "staff"), list("member"), Collections.<String>emptyList());
        assertTrue(diff.isEmpty(), "an empty managed list must not be read as full authority");
        assertEquals(Collections.emptyList(), diff.toAdd());
        assertEquals(Collections.emptyList(), diff.toRemove());
    }

    @Test
    @DisplayName("a null managed list changes nothing at all")
    void nullManagedIsInert() {
        assertTrue(GroupDiff.compute(list("vip"), list("member"), null).isEmpty());
    }

    @Test
    @DisplayName("unmanaged groups the player holds are never removed")
    void unmanagedGroupsSurvive() {
        // The player bought "donor" and was promoted to "staff"; the dashboard owns neither.
        GroupDiff diff = GroupDiff.compute(
                list("default", "donor", "staff", "linked"),
                Collections.<String>emptyList(),
                list("linked", "booster"));
        assertEquals(list("linked"), diff.toRemove());
        assertEquals(Collections.emptyList(), diff.toAdd());
    }

    @Test
    @DisplayName("a target group outside the managed set is not granted")
    void unmanagedTargetIsNotGranted() {
        // A dashboard asking for a group it does not own must not be able to hand out permissions.
        GroupDiff diff = GroupDiff.compute(
                list("default"), list("linked", "admin"), list("linked"));
        assertEquals(list("linked"), diff.toAdd());
        assertEquals(Collections.emptyList(), diff.toRemove());
    }

    @Test
    @DisplayName("a managed group already held is not granted again")
    void alreadyHeldIsNotReGranted() {
        GroupDiff diff = GroupDiff.compute(list("linked"), list("linked"), list("linked"));
        assertTrue(diff.isEmpty(), "no change means no write — see the save in LuckPermsIntegration");
    }

    @Test
    @DisplayName("adds and removes happen in the same pass")
    void addsAndRemovesTogether() {
        GroupDiff diff = GroupDiff.compute(
                list("default", "tier1"), list("tier2"), list("tier1", "tier2", "tier3"));
        assertEquals(list("tier2"), diff.toAdd());
        assertEquals(list("tier1"), diff.toRemove());
    }

    @Test
    @DisplayName("input order is preserved, so a log line reads the way the operator expects")
    void orderIsPreserved() {
        GroupDiff diff = GroupDiff.compute(
                list("c", "a", "b"), list("z", "y", "x"), list("a", "b", "c", "x", "y", "z"));
        assertEquals(list("z", "y", "x"), diff.toAdd());
        assertEquals(list("c", "a", "b"), diff.toRemove());
    }

    @Test
    @DisplayName("nulls for current and target are empty, not a crash")
    void nullsAreEmpty() {
        assertEquals(list("vip"), GroupDiff.compute(null, list("vip"), list("vip")).toAdd());
        assertEquals(list("vip"), GroupDiff.compute(list("vip"), null, list("vip")).toRemove());
    }

    @Test
    @DisplayName("comparison is case-sensitive, matching LuckPerms' own storage")
    void caseSensitive() {
        // LuckPerms lower-cases group names when they are created, so "VIP" is a name it would
        // never have stored. Folding case here would hide that rather than surface it.
        GroupDiff diff = GroupDiff.compute(list("vip"), list("VIP"), list("vip", "VIP"));
        assertEquals(list("VIP"), diff.toAdd());
        assertEquals(list("vip"), diff.toRemove());
    }

    @Test
    @DisplayName("the returned lists cannot be edited by the caller")
    void resultsAreImmutable() {
        GroupDiff diff = GroupDiff.compute(list("a"), list("b"), list("a", "b"));
        assertThrows(UnsupportedOperationException.class, () -> diff.toAdd().add("c"));
        assertThrows(UnsupportedOperationException.class, () -> diff.toRemove().add("c"));
    }
}
