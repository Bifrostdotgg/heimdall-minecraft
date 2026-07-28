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
 * <p>So this is v2's {@code compareVersions} kept exactly: strip a leading {@code v}, split on
 * {@code .}, take the leading run of digits from each component and ignore whatever follows, pad
 * the shorter side with zeros. {@code 2.1.0-rc1} therefore compares equal to {@code 2.1.0}, which
 * is deliberate — a pre-release tag must not read as <em>newer</em> than the release it precedes,
 * and offering an operator a "newer" build that is actually the release candidate of what they are
 * already running is worse than saying nothing.
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

    private static int[] parse(String version) {
        String[] parts = normalize(version).split("\\.");
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
