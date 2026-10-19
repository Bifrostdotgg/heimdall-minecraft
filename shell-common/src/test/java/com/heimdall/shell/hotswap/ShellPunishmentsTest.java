package com.heimdall.shell.hotswap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.api.HeimdallPunishment;
import com.heimdall.api.HeimdallPunishments;
import com.heimdall.core.util.Registration;
import com.heimdall.shell.contract.PunishmentBackend;
import com.heimdall.shell.contract.PunishmentRecord;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The public punishment queries other plugins hold, which live in the shell since they must outlive
 * every core (departure D89). Called from chat threads, so "no core" and "a broken core" both have to
 * answer "not punished" rather than throw.
 */
class ShellPunishmentsTest {

    private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

    private final RecordingShellLog log = new RecordingShellLog();
    private final ShellPunishments punishments = new ShellPunishments(log);

    @Test
    @DisplayName("with no core bound every query answers 'not punished' and nothing throws")
    void inertWithoutABackend() {
        assertFalse(punishments.isAvailable());
        assertFalse(punishments.isMuted(STEVE));
        assertFalse(punishments.isMuteHidden(STEVE));
        assertFalse(punishments.isBanned(STEVE));
        assertFalse(punishments.isFrozen(STEVE));
        assertNull(punishments.activePunishment(STEVE, HeimdallPunishments.TYPE_MUTE));
        assertFalse(punishments.isMuted(null));
    }

    @Test
    @DisplayName("queries forward to the bound core, and a hidden mute says so")
    void forwardsToTheBackend() {
        FakeBackend core = new FakeBackend("core-1");
        core.mute = PunishmentRecord.builder("mute", STEVE).id("p1").reason("spam")
                .expiresAtMillis(5_000L).hidden(true).build();
        punishments.bind(core);

        assertTrue(punishments.isAvailable());
        assertTrue(punishments.isMuted(STEVE));
        assertTrue(punishments.isMuteHidden(STEVE));
        assertFalse(punishments.isBanned(STEVE));
        assertFalse(punishments.isMuted(UUID.randomUUID()));

        HeimdallPunishment mute = punishments.activePunishment(STEVE, HeimdallPunishments.TYPE_MUTE);
        assertNotNull(mute);
        assertEquals("p1", mute.id());
        assertEquals("mute", mute.type());
        assertEquals(STEVE, mute.player());
        assertEquals("spam", mute.reason());
        assertEquals(5_000L, mute.expiresAtMillis());
        assertFalse(mute.isPermanent());
        assertTrue(mute.hidden());
    }

    @Test
    @DisplayName("a visible mute is muted but not hidden")
    void visibleMute() {
        FakeBackend core = new FakeBackend("core-1");
        core.mute = PunishmentRecord.builder("mute", STEVE).build();
        punishments.bind(core);

        assertTrue(punishments.isMuted(STEVE));
        assertFalse(punishments.isMuteHidden(STEVE));
        assertTrue(punishments.activePunishment(STEVE, "mute").isPermanent());
    }

    @Test
    @DisplayName("the same object answers from the next core after a swap, and from none between")
    void survivesASwap() {
        FakeBackend first = new FakeBackend("core-1");
        first.mute = PunishmentRecord.builder("mute", STEVE).id("old").build();
        Registration bound = punishments.bind(first);
        assertTrue(punishments.isMuted(STEVE));

        bound.close();
        assertFalse(punishments.isMuted(STEVE), "between cores nobody is reported muted");
        assertFalse(punishments.isAvailable());

        FakeBackend second = new FakeBackend("core-2");
        second.mute = PunishmentRecord.builder("mute", STEVE).id("new").build();
        punishments.bind(second);
        assertEquals("new", punishments.activePunishment(STEVE, "mute").id());
        first.calls = 0;
        punishments.isMuted(STEVE);
        assertEquals(0, first.calls, "a stopped core must never be called");
    }

    @Test
    @DisplayName("closing a stale handle does not unbind the core that replaced it")
    void staleCloseKeepsTheCurrentBackend() {
        Registration first = punishments.bind(new FakeBackend("core-1"));
        punishments.bind(new FakeBackend("core-2"));

        first.close();

        assertTrue(punishments.hasBackend());
        assertTrue(punishments.isAvailable());
    }

    @Test
    @DisplayName("an unknown type is never forwarded")
    void unknownTypeAnswersNull() {
        FakeBackend core = new FakeBackend("core-1");
        punishments.bind(core);

        assertNull(punishments.activePunishment(STEVE, "warn"));
        assertNull(punishments.activePunishment(STEVE, null));
        assertEquals(0, core.calls);
    }

    @Test
    @DisplayName("a backend that throws answers 'not punished' and is logged once")
    void throwingBackendIsContained() {
        punishments.bind(new PunishmentBackend() {
            @Override
            public boolean isAvailable() {
                throw new IllegalStateException("broken");
            }

            @Override
            public PunishmentRecord active(UUID player, String type) {
                throw new IllegalStateException("broken");
            }
        });

        assertFalse(punishments.isAvailable());
        assertFalse(punishments.isMuted(STEVE));
        assertFalse(punishments.isMuteHidden(STEVE));
        assertEquals(1, log.errors().size(), log.lines().toString());
    }

    private static final class FakeBackend implements PunishmentBackend {

        private final String name;
        PunishmentRecord mute;
        int calls;

        FakeBackend(String name) {
            this.name = name;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public PunishmentRecord active(UUID player, String type) {
            calls++;
            return mute != null && mute.player().equals(player) && mute.type().equals(type)
                    ? mute : null;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
