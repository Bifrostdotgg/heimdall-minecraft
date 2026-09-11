package com.heimdall.core.punish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole table of {@code -s} / {@code -p} against the guild default.
 *
 * <p>Five cases, and the interesting ones are the two that are <em>not</em> a refusal: a flag that
 * agrees with the default has overridden nothing, and refusing there would make the permission
 * about typing a flag rather than about the decision it expresses.
 */
class SilenceDecisionTest {

    private static final boolean MAY = true;
    private static final boolean MAY_NOT = false;
    private static final boolean DEFAULT_SILENT = true;
    private static final boolean DEFAULT_PUBLIC = false;

    @Test
    @DisplayName("no flag takes the guild default, whichever way it points")
    void noFlagFollowsTheDefault() {
        SilenceDecision silentGuild = SilenceDecision.decide(false, false, DEFAULT_SILENT, MAY_NOT);
        assertFalse(silentGuild.refused());
        assertTrue(silentGuild.silent());

        SilenceDecision publicGuild = SilenceDecision.decide(false, false, DEFAULT_PUBLIC, MAY_NOT);
        assertFalse(publicGuild.refused());
        assertFalse(publicGuild.silent());
    }

    @Test
    @DisplayName("-s on an announcing guild without the node is refused, not ignored")
    void silentWithoutPermissionIsRefused() {
        SilenceDecision decision = SilenceDecision.decide(true, false, DEFAULT_PUBLIC, MAY_NOT);

        assertTrue(decision.refused(),
                "ignoring the flag would punish the player and announce it, while the moderator "
                        + "believed they had asked for the opposite");
    }

    @Test
    @DisplayName("-s on an announcing guild with the node is silent")
    void silentWithPermission() {
        SilenceDecision decision = SilenceDecision.decide(true, false, DEFAULT_PUBLIC, MAY);

        assertFalse(decision.refused());
        assertTrue(decision.silent());
    }

    @Test
    @DisplayName("-p on a silent guild with the node is public")
    void publicWithPermission() {
        SilenceDecision decision = SilenceDecision.decide(false, true, DEFAULT_SILENT, MAY);

        assertFalse(decision.refused());
        assertFalse(decision.silent());
    }

    @Test
    @DisplayName("-p on a silent guild without the node is refused too")
    void publicWithoutPermissionIsRefused() {
        SilenceDecision decision = SilenceDecision.decide(false, true, DEFAULT_SILENT, MAY_NOT);

        assertTrue(decision.refused(),
                "the node is about departing from the default, not about the -s spelling: making "
                        + "a quiet guild's punishment loud is the same decision in reverse");
    }

    @Test
    @DisplayName("a flag that agrees with the default is a no-op and needs no permission")
    void agreeingFlagIsAllowed() {
        SilenceDecision silent = SilenceDecision.decide(true, false, DEFAULT_SILENT, MAY_NOT);
        assertFalse(silent.refused());
        assertTrue(silent.silent());

        SilenceDecision announced = SilenceDecision.decide(false, true, DEFAULT_PUBLIC, MAY_NOT);
        assertFalse(announced.refused());
        assertFalse(announced.silent());
    }

    @Test
    @DisplayName("both flags at once resolve to silent, the narrower audience")
    void silentWinsOverPublic() {
        SilenceDecision decision = SilenceDecision.decide(true, true, DEFAULT_PUBLIC, MAY);

        assertFalse(decision.refused());
        assertTrue(decision.silent(),
                "the narrow audience cannot leak something the wide one would have kept");
    }

    @Test
    @DisplayName("heimdall.admin grants the override, as it grants the notify node")
    void adminMayOverride() {
        assertTrue(SilenceDecision.mayOverride(false, true),
                "the Bukkit descriptor declares the node a child of heimdall.admin, and a "
                        + "descriptor that says a permission is held while the code refuses it is "
                        + "worse than either answer on its own");
        assertTrue(SilenceDecision.mayOverride(true, false));
        assertTrue(SilenceDecision.mayOverride(true, true));
        assertFalse(SilenceDecision.mayOverride(false, false));

        SilenceDecision admin = SilenceDecision.decide(
                true, false, DEFAULT_PUBLIC, SilenceDecision.mayOverride(false, true));
        assertFalse(admin.refused());
        assertTrue(admin.silent());
    }

    @Test
    @DisplayName("a refused decision still reports the default, so a missed check cannot invert it")
    void refusedKeepsTheDefault() {
        assertFalse(SilenceDecision.decide(true, false, DEFAULT_PUBLIC, MAY_NOT).silent());
        assertTrue(SilenceDecision.decide(false, true, DEFAULT_SILENT, MAY_NOT).silent());
    }
}
