package com.heimdall.core.punish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PunishmentIpTest {

    private static final String GOLDEN =
            "95c86990ffbd1a90697def210d49c78b1c55f3bde5db3dfd08c4b1bfa80e1ecb";

    @Test
    void stripsIpv4MappedIpv6() {
        assertEquals("1.2.3.4", PunishmentIp.canonical("::ffff:1.2.3.4"));
        assertEquals("1.2.3.4", PunishmentIp.canonical("  ::FFFF:1.2.3.4  "));
        assertEquals("1.2.3.4", PunishmentIp.canonical("1.2.3.4"));
    }

    @Test
    void lowerCasesIpv6WithoutExpanding() {
        assertEquals("2001:db8::1", PunishmentIp.canonical("2001:DB8::1"));
    }

    @Test
    void sameIpAndSaltIsStableAndMatchesTypescript() {
        assertEquals(GOLDEN, PunishmentIp.hash("1.2.3.4", "salt"));
        assertEquals(GOLDEN, PunishmentIp.hash("::ffff:1.2.3.4", "salt"));
        assertEquals(PunishmentIp.hash("1.2.3.4", "salt"), PunishmentIp.hash("::ffff:1.2.3.4", "salt"));
    }

    @Test
    void differentSaltDiffers() {
        assertNotEquals(PunishmentIp.hash("1.2.3.4", "a"), PunishmentIp.hash("1.2.3.4", "b"));
    }

    @Test
    void blankIpIsEmptyDigest() {
        assertEquals("", PunishmentIp.hash("", "salt"));
        assertEquals("", PunishmentIp.hash(null, "salt"));
        assertTrue(PunishmentIp.canonical(null).isEmpty());
    }

    @Test
    void emptySaltThrows() {
        assertThrows(IllegalArgumentException.class, () -> PunishmentIp.hash("1.2.3.4", ""));
        assertThrows(IllegalArgumentException.class, () -> PunishmentIp.hash("1.2.3.4", null));
        assertThrows(IllegalArgumentException.class, () -> PunishmentIp.hash("", ""));
    }
}
