package com.heimdall.platform.bukkit.paper;

import org.bukkit.Bukkit;

/**
 * Detects whether the running server exposes the Paper API.
 *
 * <p>Everything in this module is reached only after {@link #isPaper()} returns {@code true}, so
 * the shared jar never links a Paper class on a plain Spigot server.
 */
public final class PaperSupport {

    private PaperSupport() {
    }

    /** Whether the running server exposes the Paper API. */
    public static boolean isPaper() {
        try {
            Class.forName("com.destroystokyo.paper.PaperConfig");
            return true;
        } catch (ClassNotFoundException legacyMissing) {
            try {
                Class.forName("io.papermc.paper.configuration.Configuration");
                return true;
            } catch (ClassNotFoundException modernMissing) {
                return false;
            }
        }
    }

    /** The server implementation string, used in phase 0 only for logging. */
    public static String describeServer() {
        return Bukkit.getName() + " " + Bukkit.getVersion();
    }
}
