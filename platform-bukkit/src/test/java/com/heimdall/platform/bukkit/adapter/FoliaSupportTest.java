package com.heimdall.platform.bukkit.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The probe must not throw when there is no server.
 *
 * <p>A capability check that explodes in tests, or during a half-started enable, would make the
 * Folia adapter look like a crash on every Spigot boot. {@code false} is the only honest answer
 * here: there is no {@code Server#getGlobalRegionScheduler} to find.
 */
class FoliaSupportTest {

    @Test
    @DisplayName("a process with no Bukkit server is not a regionised server")
    void noServerMeansNoRegionSchedulers() {
        assertFalse(FoliaSupport.hasRegionSchedulers());
    }
}
