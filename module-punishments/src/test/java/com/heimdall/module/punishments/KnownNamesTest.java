package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The completion index, without a server.
 *
 * <p>The half worth testing here is the live-punishment set, because it is what {@code /unban}
 * reads on a keystroke and it is maintained by writes rather than derived from the mirror. The
 * name half is exercised end-to-end in {@code PunishmentCompletionTest}.
 */
class KnownNamesTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    @DisplayName("a punished name is a candidate; a lifted one is not")
    void punishedAndLifted() {
        KnownNames names = new KnownNames();
        names.punished("ban", "Steve", KnownNames.NEVER);

        assertTrue(names.withActive("ban", NOW).test("steve"),
                "matched lower-cased, because that is how a moderator types it");
        assertFalse(names.withActive("mute", NOW).test("steve"),
                "and only in the family it is in");

        names.unpunished("ban", "steve");
        assertFalse(names.withActive("ban", NOW).test("steve"));
    }

    @Test
    @DisplayName("a punishment that has run out stops being a candidate on its own")
    void expiredEntriesDropThemselves() {
        KnownNames names = new KnownNames();
        names.punished("mute", "Alex", NOW + 60_000L);

        assertTrue(names.withActive("mute", NOW).test("alex"));
        assertFalse(names.withActive("mute", NOW + 60_001L).test("alex"),
                "nothing tells the index a tempmute ran out, so the read has to notice");
        assertFalse(names.withActive("mute", NOW).test("alex"),
                "and it is gone rather than merely hidden, so the map cannot grow on dead rows");
    }

    @Test
    @DisplayName("a family nobody has been punished in answers no to everything")
    void unknownFamiliesAreEmpty() {
        KnownNames names = new KnownNames();

        assertFalse(names.withActive("warn", NOW).test("steve"));
        assertFalse(names.withActive("kick", NOW).test("steve"),
                "a kick has no revoke verb that names a player, so it is not a family at all");
        names.punished("kick", "Steve", KnownNames.NEVER);
        assertFalse(names.withActive("kick", NOW).test("steve"));
    }

    @Test
    @DisplayName("installing a rebuilt index replaces the old one whole")
    void installReplaces() {
        KnownNames names = new KnownNames();
        names.punished("ban", "Steve", KnownNames.NEVER);

        names.install(KnownNames.index()
                .add("ban", "Alex", KnownNames.NEVER)
                .add("warn", "Notch", NOW + 1000L));

        assertFalse(names.withActive("ban", NOW).test("steve"),
                "a sync is authoritative: a row it did not list is not in the mirror either");
        assertTrue(names.withActive("ban", NOW).test("alex"));
        assertTrue(names.withActive("warn", NOW).test("notch"));
    }

    @Test
    @DisplayName("the longest of a player's rows in one family decides, because unban lifts both")
    void longestRowWins() {
        KnownNames names = new KnownNames();

        names.install(KnownNames.index()
                .add("ban", "Steve", NOW + 1000L)
                .add("ban", "Steve", KnownNames.NEVER));

        assertTrue(names.withActive("ban", NOW + 5000L).test("steve"),
                "the expiring ban is over, the permanent IP ban is not, and /unban takes both");
    }

    @Test
    @DisplayName("the filter is asked about candidates, not asked for a list of them")
    void theFilterIsAskedPerCandidate() {
        KnownNames names = new KnownNames();
        names.joined("Steve");
        names.joined("Stella");
        names.joined("Notch");
        final java.util.List<String> asked = new java.util.ArrayList<String>();

        names.matching("Ste", 10, key -> {
            asked.add(key);
            return true;
        });

        assertEquals(Arrays.asList("stella", "steve"), asked,
                "only the names the prefix scan reached, so a server with twenty thousand active "
                        + "bans does not build a twenty-thousand-entry set for one keystroke");
        assertEquals(Collections.emptyList(), names.matching("Ste", 10, key -> false));
    }

    @Test
    @DisplayName("the filter is not asked past the limit either")
    void theFilterStopsAtTheLimit() {
        KnownNames names = new KnownNames();
        for (int i = 0; i < 50; i++) {
            names.joined(String.format("Player%02d", i));
        }
        final int[] asked = new int[1];

        names.matching("Player", 5, key -> {
            asked[0]++;
            return true;
        });

        assertEquals(5, asked[0], "the scan stops once it has enough, and so does the filter");
    }
}
