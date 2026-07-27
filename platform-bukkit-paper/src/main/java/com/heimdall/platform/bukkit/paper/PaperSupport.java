package com.heimdall.platform.bukkit.paper;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;

/**
 * Capability probes for the Paper-only API this module uses.
 *
 * <p><strong>Probed by capability, never by brand.</strong> "Is this Paper?" is the question that
 * looks right and is wrong in both directions: Paper 1.12.2 answers yes and has none of the API a
 * 1.16-era check would then call, while Purpur, Pufferfish and Folia answer no and have all of it.
 * Both mistakes surface as a {@code NoSuchMethodError} on a customer's server.
 *
 * <p>So the probe asks for the methods themselves. {@code Server#getTPS()} is a Paper addition that
 * Spigot has never had; {@code getAverageTickTime()} arrived later still, which is why they are
 * checked separately and MSPT can be absent on a server that reports TPS perfectly well.
 *
 * <p>Reflection is used here rather than a direct call precisely because this class has to be
 * <em>safe to load</em> on a server that has neither. A method body that named {@code
 * Bukkit.getTPS()} would be verified — and fail — the moment the class was linked, however
 * carefully the code around it checked first. {@link PaperTickSource} is the class that makes the
 * direct calls, and nothing loads it until these probes have said yes.
 */
public final class PaperSupport {

    private PaperSupport() {
    }

    /** Whether {@code Server#getTPS()} exists on this server. */
    public static boolean hasTickApi() {
        return hasServerMethod("getTPS");
    }

    /** Whether {@code Server#getAverageTickTime()} exists. Paper 1.16+; absent on older Paper. */
    public static boolean hasMsptApi() {
        return hasServerMethod("getAverageTickTime");
    }

    private static boolean hasServerMethod(String name) {
        try {
            // Asked of the running server's own class, which is what a virtual call would dispatch
            // on. getMethod walks up to the API interface, so a method declared only on Paper's
            // org.bukkit.Server is found either way — and a fork that adds one without implementing
            // the interface is found too, which is the point of probing for the capability rather
            // than for the brand.
            return Bukkit.getServer().getClass().getMethod(name) != null;
        } catch (Throwable absent) {
            return false;
        }
    }
}
