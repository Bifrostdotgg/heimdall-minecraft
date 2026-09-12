package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertTrue(names.withActive("ban", NOW).contains("steve"),
                "matched lower-cased, because that is how a moderator types it");
        assertTrue(names.withActive("mute", NOW).isEmpty(), "and only in the family it is in");

        names.unpunished("ban", "steve");
        assertTrue(names.withActive("ban", NOW).isEmpty());
    }

    @Test
    @DisplayName("a punishment that has run out stops being a candidate on its own")
    void expiredEntriesDropThemselves() {
        KnownNames names = new KnownNames();
        names.punished("mute", "Alex", NOW + 60_000L);

        assertTrue(names.withActive("mute", NOW).contains("alex"));
        assertTrue(names.withActive("mute", NOW + 60_001L).isEmpty(),
                "nothing tells the index a tempmute ran out, so the read has to notice");
        assertTrue(names.withActive("mute", NOW).isEmpty(),
                "and it is gone rather than merely hidden, so the map cannot grow on dead rows");
    }

    @Test
    @DisplayName("a family nobody has been punished in answers empty rather than null")
    void unknownFamiliesAreEmpty() {
        KnownNames names = new KnownNames();

        assertEquals(Collections.emptySet(), names.withActive("warn", NOW));
        assertEquals(Collections.emptySet(), names.withActive("kick", NOW),
                "a kick has no revoke verb that names a player, so it is not a family at all");
        names.punished("kick", "Steve", KnownNames.NEVER);
        assertEquals(Collections.emptySet(), names.withActive("kick", NOW));
    }

    @Test
    @DisplayName("installing a rebuilt index replaces the old one whole")
    void installReplaces() {
        KnownNames names = new KnownNames();
        names.punished("ban", "Steve", KnownNames.NEVER);

        names.install(KnownNames.index()
                .add("ban", "Alex", KnownNames.NEVER)
                .add("warn", "Notch", NOW + 1000L));

        assertFalse(names.withActive("ban", NOW).contains("steve"),
                "a sync is authoritative: a row it did not list is not in the mirror either");
        assertTrue(names.withActive("ban", NOW).contains("alex"));
        assertTrue(names.withActive("warn", NOW).contains("notch"));
    }

    @Test
    @DisplayName("the longest of a player's rows in one family decides, because unban lifts both")
    void longestRowWins() {
        KnownNames names = new KnownNames();

        names.install(KnownNames.index()
                .add("ban", "Steve", NOW + 1000L)
                .add("ban", "Steve", KnownNames.NEVER));

        assertTrue(names.withActive("ban", NOW + 5000L).contains("steve"),
                "the expiring ban is over, the permanent IP ban is not, and /unban takes both");
    }
}
