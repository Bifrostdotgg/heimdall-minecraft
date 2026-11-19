package com.heimdall.core.update;

/**
 * Ordering for the version strings the bot reports, which are not semver and cannot be assumed to
 * be.
 *
 * <p>v2 compared versions in three places and got it wrong in two of them. The release feed carries
 * whatever a GitHub tag happened to say — {@code v3.0.0}, {@code 3.0.0}, {@code 3.0.0-SNAPSHOT},
 * {@code 2.1.0-rc1}, occasionally a two-component {@code 3.1} — and the two obvious shortcuts both
 * fail on real data. String comparison puts {@code 10.0.0} <em>before</em> {@code 9.9.9}, which is
 * the failure that stops an entire fleet ever seeing a major release. Splitting on {@code .} and
 * calling {@link Integer#parseInt} throws on {@code 0-rc1}, and a thrown exception inside an update
 * check is swallowed, so the symptom is "update checks silently stopped working" with nothing in
 * the log.
 *
 * <p>So the numeric part is v2's {@code compareVersions} kept: strip a leading {@code v}, split on
 * {@code .}, take the leading run of digits from each component and ignore whatever follows, pad
 * the shorter side with zeros.
 *
 * <p>What v2 did not have is a pre-release rule, and the v3 tags showed why it needs one. Its
 * split ran over the whole string, so {@code 3.0.0-rc.25} became {@code 3, 0, 0-rc, 25}, which is
 * {@code [3, 0, 0, 25]}: <em>newer</em> than {@code 3.0.0}. Every server on a release candidate
 * would have called itself up to date and refused the release it was waiting for, which is why
 * the first stable v3 is 3.0.1. So the version is now cut at its first {@code -} into a numeric
 * core and a pre-release, and ordered the way SemVer 2.0 orders them: cores first; on equal cores a
 * release outranks any pre-release of it; two pre-releases compare identifier by identifier, numeric
 * ones by value ({@code rc.10} after {@code rc.9}) and below alphanumeric ones, and a shorter list
 * that is a prefix of a longer one sorts first. Build metadata after {@code +} is ignored.
 *
 * <p>A pre-release still never reads as <em>newer</em> than the release it precedes, so an operator
 * on {@code 2.1.0} is not offered {@code 2.1.0-rc1}; but an operator on {@code 2.1.0-rc1} now is
 * offered {@code 2.1.0}, which v2's rule (the two compared equal) withheld.
 *
 * <p><strong>Nothing here throws.</strong> Not on {@code null}, not on the empty string, not on
 * {@code "abc"} — a garbage component is worth zero. An update check is nobody's emergency, and a
 * malformed tag on the bot's side must not be able to produce an exception on a Minecraft server.
 *
 * <p><strong>Threading.</strong> Stateless static functions on immutable inputs. Callable from any
 * thread, including the main server thread; they do no I/O and allocate a handful of small arrays.
 */
public final class Versions {

    private Versions() {
    }

    /**
     * Orders two version strings.
     *
     * @return negative if {@code a} is older than {@code b}, zero if they are equivalent, positive
     *     if {@code a} is newer
     */
    public static int compare(String a, String b) {
        String na = stripBuild(normalize(a));
        String nb = stripBuild(normalize(b));
        int byCore = compareCores(core(na), core(nb));
        if (byCore != 0) {
            return byCore;
        }
        return comparePreReleases(preRelease(na), preRelease(nb));
    }

    /**
     * Whether {@code candidate} is strictly newer than {@code current}.
     *
     * <p>The question {@link UpdateService} actually asks, named so the call site does not have to
     * spell out which side of a {@code compare(...) < 0} is which. v2 wrote that comparison out at
     * every call site and one of them had the arguments the wrong way round.
     */
    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    /**
     * Strips a leading {@code v} or {@code V} and surrounding whitespace.
     *
     * <p>Public because it is also what an operator-facing message should print: the banner says
     * "Latest: 3.1.0", not "Latest: v3.1.0", whichever form the tag happened to take. Returns
     * {@code "0"} for {@code null}, the empty string, and a bare {@code v}.
     */
    public static String normalize(String version) {
        if (version == null) {
            return "0";
        }
        String trimmed = version.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.isEmpty() ? "0" : trimmed;
    }

    private static int compareCores(String a, String b) {
        int[] pa = parse(a);
        int[] pb = parse(b);
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? pa[i] : 0;
            int vb = i < pb.length ? pb[i] : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    /**
     * SemVer pre-release precedence, where {@code null} is "no pre-release" and outranks any.
     *
     * <p>Identifiers are compared as strings, never parsed, so a numeric identifier too long for any
     * integer type still orders correctly and cannot throw.
     */
    private static int comparePreReleases(String a, String b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : 1) : -1;
        }
        String[] ia = a.split("\\.", -1);
        String[] ib = b.split("\\.", -1);
        int len = Math.min(ia.length, ib.length);
        for (int i = 0; i < len; i++) {
            int c = compareIdentifiers(ia[i], ib[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(ia.length, ib.length);
    }

    private static int compareIdentifiers(String a, String b) {
        boolean numericA = isNumeric(a);
        boolean numericB = isNumeric(b);
        if (numericA && numericB) {
            String ta = stripLeadingZeros(a);
            String tb = stripLeadingZeros(b);
            if (ta.length() != tb.length()) {
                return Integer.compare(ta.length(), tb.length());
            }
            return Integer.signum(ta.compareTo(tb));
        }
        if (numericA != numericB) {
            return numericA ? -1 : 1;
        }
        return Integer.signum(a.compareTo(b));
    }

    /** ASCII digits only: SemVer identifiers are ASCII, and anything else sorts as text. */
    private static boolean isNumeric(String identifier) {
        if (identifier.isEmpty()) {
            return false;
        }
        for (int i = 0; i < identifier.length(); i++) {
            char c = identifier.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static String stripLeadingZeros(String digits) {
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }
        return digits.substring(i);
    }

    private static String stripBuild(String version) {
        int plus = version.indexOf('+');
        return plus < 0 ? version : version.substring(0, plus);
    }

    /** Everything before the first {@code -}. */
    private static String core(String version) {
        int dash = version.indexOf('-');
        return dash < 0 ? version : version.substring(0, dash);
    }

    /**
     * Everything after the first {@code -}, or {@code null} for a release.
     *
     * <p>Also {@code null} when the core has no digit in it at all: {@code -rc1} or {@code v-beta}
     * is garbage, and garbage is worth exactly zero, not "a pre-release of zero" that sorts below it.
     */
    private static String preRelease(String version) {
        int dash = version.indexOf('-');
        if (dash < 0 || dash == version.length() - 1 || !hasDigit(version.substring(0, dash))) {
            return null;
        }
        return version.substring(dash + 1);
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static int[] parse(String core) {
        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = leadingInt(parts[i]);
        }
        return out;
    }

    /** The leading run of digits in a component, ignoring any suffix. Zero if there is none. */
    private static int leadingInt(String component) {
        int end = 0;
        while (end < component.length() && Character.isDigit(component.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(component.substring(0, end));
        } catch (NumberFormatException e) {
            // A run of digits too long for an int — "20250101120000" as a date-stamped tag is the
            // realistic case. Worth zero rather than worth an exception.
            return 0;
        }
    }
}
