package com.heimdall.core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Version ordering, which v2 shipped with no tests at all.
 *
 * <p>The two cases worth naming are the ones a plausible wrong implementation gets wrong: string
 * comparison puts {@code 10.0.0} before {@code 9.9.9}, and {@code Integer.parseInt} throws on
 * {@code 0-rc1}. Both are here.
 */
class VersionsTest {

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @ParameterizedTest(name = "{0} vs {1} -> {2}")
        @CsvSource({
            // equal, in every spelling the release feed produces
            "3.0.0,      3.0.0,          0",
            "v3.0.0,     3.0.0,          0",
            "3.0.0,      V3.0.0,         0",
            "'  3.0.0 ', 3.0.0,          0",
            "3.0.0+build.9, 3.0.0,       0",
            "3.0.0-rc.6, v3.0.0-rc.6,    0",
            // older
            "3.0.0-SNAPSHOT, 3.0.0,     -1",
            "2.1.0-rc1,  2.1.0,         -1",
            "2.4.0,      3.0.0,         -1",
            "3.0,        3.0.1,         -1",
            "3.0.9,      3.1,           -1",
            "9.9.9,      10.0.0,        -1",
            // newer
            "3.0.0,      2.4.0,          1",
            "3.1,        3.0.9,          1",
            "10.0.0,     9.9.9,          1",
            "3.0.1,      3.0,            1",
            // pre-releases, as the v3 tags actually spell them
            "3.0.0-rc.25, 3.0.0,        -1",
            "3.0.0-rc.25, 3.0.1,        -1",
            "3.0.0-rc.9, 3.0.0-rc.10,   -1",
            "3.0.0-rc.24, 3.0.0-rc.25,  -1",
            "3.0.0-rc,   3.0.0-rc.1,    -1",
            "3.0.0-1,    3.0.0-alpha,   -1",
            "3.0.0-alpha, 3.0.0-beta,   -1",
            "3.0.0-rc.6, 3.0.0-rc.6-folia-experimental, -1",
            "2.4.0,      3.0.0-rc.1,    -1",
            "3.1.0-rc.1, 3.1.0,         -1",
            "3.0.1,      3.1.0-rc.1,    -1",
            "3.0.0-rc.99999999999999999999, 3.0.0-rc.100000000000000000000, -1",
        })
        void ordersVersions(String a, String b, int expected) {
            assertEquals(expected, Integer.signum(Versions.compare(a, b)),
                    "compare(" + a + ", " + b + ")");
            // Antisymmetry, checked on every row rather than as its own case.
            assertEquals(-expected, Integer.signum(Versions.compare(b, a)),
                    "compare(" + b + ", " + a + ")");
        }

        @Test
        @DisplayName("a pre-release is older than the release it precedes")
        void preReleaseIsOlder() {
            // Offering an operator running 2.1.0 an "update" to 2.1.0-rc1 would be offering them a
            // downgrade.
            assertFalse(Versions.isNewer("2.1.0-rc1", "2.1.0"));
            // And an operator on a release candidate must be offered the release. Before the
            // pre-release rule, 3.0.0-rc.25 read as [3, 0, 0, 25] and outranked 3.0.0, so no rc
            // server would ever have been told 3.0.0 was out.
            assertTrue(Versions.isNewer("3.0.0", "3.0.0-rc.25"));
            assertTrue(Versions.isNewer("3.0.1", "3.0.0-rc.25"));
            assertTrue(Versions.isNewer("3.0.0-rc.26", "3.0.0-rc.25"));
        }

        @Test
        @DisplayName("isNewer reads the arguments in the documented order")
        void isNewerDirection() {
            assertTrue(Versions.isNewer("3.1.0", "3.0.0"));
            assertFalse(Versions.isNewer("3.0.0", "3.1.0"));
            assertFalse(Versions.isNewer("3.0.0", "3.0.0"));
        }
    }

    @Nested
    @DisplayName("garbage in")
    class GarbageIn {

        @ParameterizedTest(name = "\"{0}\" is worth zero")
        @ValueSource(strings = {"abc", "", "   ", "v", ".", "..", "-rc1", "v-beta", "99999999999999999999"})
        void treatedAsZero(String garbage) {
            assertEquals(0, Versions.compare(garbage, "0"), "compare(\"" + garbage + "\", \"0\")");
            assertTrue(Versions.isNewer("0.0.1", garbage), "0.0.1 should beat \"" + garbage + "\"");
        }

        @Test
        @DisplayName("null is tolerated on either side")
        void nullTolerated() {
            assertEquals(0, Versions.compare(null, null));
            assertEquals(0, Versions.compare(null, "0"));
            assertTrue(Versions.isNewer("1.0.0", null));
            assertFalse(Versions.isNewer(null, "1.0.0"));
        }

        @Test
        @DisplayName("nothing throws, whatever the tag says")
        void neverThrows() {
            List<String> nasty = Arrays.asList(
                    null, "", "v", "...", "1.2.3.4.5.6.7.8", "1.-2.3", "١.٢.٣", "1e10", "🙂",
                    "1.0.0-", "1.0.0-.", "1.0.0-rc..1", "1.0.0+", "-", "+", "1-2-3", "1.0.0-٣");
            for (String a : nasty) {
                for (String b : nasty) {
                    Versions.compare(a, b);
                    Versions.isNewer(a, b);
                    Versions.normalize(a);
                }
            }
        }
    }

    @Nested
    @DisplayName("normalize")
    class Normalize {

        @Test
        void stripsALeadingVAndWhitespace() {
            assertEquals("3.0.0", Versions.normalize("v3.0.0"));
            assertEquals("3.0.0", Versions.normalize("V3.0.0"));
            assertEquals("3.0.0", Versions.normalize("  3.0.0  "));
            assertEquals("3.0.0-rc1", Versions.normalize("v3.0.0-rc1"));
        }

        @Test
        void answersZeroForNothing() {
            assertEquals("0", Versions.normalize(null));
            assertEquals("0", Versions.normalize(""));
            assertEquals("0", Versions.normalize("   "));
            assertEquals("0", Versions.normalize("v"));
        }
    }
}
