package heimdall.conformance.fixtures;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;

/**
 * Deliberate violation of the Adventure half of {@link
 * heimdall.conformance.HeimdallRules#platformIsolation}: the Adventure <em>platform</em> bindings
 * pull in server types and are banned from platform-free code.
 *
 * <p>Its counterpart {@link PlatformFreeAdventureFixture} proves the ban stops here rather than
 * covering Adventure as a whole.
 */
public final class AdventurePlatformFixture {

    private AdventurePlatformFixture() {
    }

    /** References an Adventure platform binding, which platform-free code may not do. */
    public static Class<?> leak() {
        return BukkitAudiences.class;
    }
}
