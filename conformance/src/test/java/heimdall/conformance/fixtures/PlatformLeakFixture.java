package heimdall.conformance.fixtures;

import org.bukkit.Bukkit;

/**
 * Deliberate violation of {@link heimdall.conformance.HeimdallRules#platformIsolation}.
 *
 * <p>Lives outside {@code com.heimdall} so the production rule never sees it; the test points the
 * same rule at this package to prove it fires.
 */
public final class PlatformLeakFixture {

    private PlatformLeakFixture() {
    }

    /** Touches a Bukkit type, which platform-free code may not do. */
    public static String leak() {
        return Bukkit.getVersion();
    }
}
