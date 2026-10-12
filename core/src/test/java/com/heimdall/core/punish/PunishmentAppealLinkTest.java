package com.heimdall.core.punish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The appeal link names the punishment, so the appeal lands on its case. */
class PunishmentAppealLinkTest {

    private static final String ID = "0123456789abcdef01234567";

    @Test
    @DisplayName("a synced punishment's id rides on the link")
    void addsRef() {
        assertEquals("https://appeals.example/abc?ref=" + ID,
                PunishmentView.appealLink("https://appeals.example/abc", ID));
        assertEquals("https://appeals.example/abc?type=minecraft&ref=" + ID,
                PunishmentView.appealLink("https://appeals.example/abc?type=minecraft", ID));
        assertEquals("https://appeals.example/abc?ref=" + ID,
                PunishmentView.appealLink("https://appeals.example/abc", ID.toUpperCase()));
        // Another parameter that merely ends in "ref" is not a ref.
        assertEquals("https://appeals.example/abc?xref=1&ref=" + ID,
                PunishmentView.appealLink("https://appeals.example/abc?xref=1", ID));
    }

    @Test
    @DisplayName("a row the bot has not seen, or a link that cannot take one, stays as it is")
    void leavesLink() {
        assertEquals("https://appeals.example/abc",
                PunishmentView.appealLink("https://appeals.example/abc", "local-1234"));
        assertEquals("https://appeals.example/abc",
                PunishmentView.appealLink("https://appeals.example/abc", null));
        assertEquals("https://appeals.example/abc?ref=7",
                PunishmentView.appealLink("https://appeals.example/abc?ref=7", ID));
        assertEquals("https://appeals.example/abc#x",
                PunishmentView.appealLink("https://appeals.example/abc#x", ID));
        assertEquals("", PunishmentView.appealLink("", ID));
        assertEquals("", PunishmentView.appealLink(null, ID));
    }

    @Test
    @DisplayName("the screen token carries it")
    void token() {
        PunishmentView view = PunishmentView.builder()
                .type("ban").id(ID).appealUrl("https://appeals.example/abc").build();
        assertEquals("https://appeals.example/abc?ref=" + ID,
                com.heimdall.core.text.Template.fill("{appeal_url}", view.tokens(0L)));
    }
}
