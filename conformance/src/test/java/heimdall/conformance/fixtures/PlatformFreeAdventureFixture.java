package heimdall.conformance.fixtures;

import net.kyori.adventure.text.Component;

/**
 * The negative control for the platform-isolation rule.
 *
 * <p>Adventure's text model is platform-free and core is designed to build messages with it, so
 * this class must <em>not</em> be reported. Without this fixture, tightening the rule to ban all of
 * {@code net.kyori} would still look green everywhere and only surface as a wall of violations the
 * day the message renderer lands.
 */
public final class PlatformFreeAdventureFixture {

    private PlatformFreeAdventureFixture() {
    }

    /** Builds a message with Adventure's platform-free API. */
    public static Component greeting() {
        return Component.text("Heimdall");
    }
}
