package com.heimdall.platform.bukkit.adapter;

import com.heimdall.core.log.HeimdallLogger;

/**
 * Picks the implementation that fits the server this jar happens to have landed on.
 *
 * <p>One jar covers Spigot 1.8.8 through Paper 1.21, and the API genuinely differs across that
 * range. The rule this class follows is <strong>probe for the capability, not for the
 * brand</strong>: a server is asked whether it can report a tick rate, never whether it is "Paper".
 *
 * <p>That distinction is not pedantry. Paper 1.12.2 answers yes to "are you Paper" and has none of
 * the API a 1.16-era check would then reach for; Purpur, Pufferfish and Folia answer no to a
 * brand check while having every method involved. Both mistakes fail at runtime, on a customer's
 * server, with a {@code NoSuchMethodError} that names a class nobody has heard of.
 *
 * <p>Every probe is a {@link Class#forName} or a reflective lookup wrapped in {@code catch
 * Throwable}, and every failure resolves to a working degraded implementation rather than to an
 * exception. A capability nobody can supply is a field the dashboard does not render.
 */
public final class BukkitAdapters {

    private BukkitAdapters() {
    }

    /**
     * The best available tick source, in order of fidelity.
     *
     * <ol>
     *   <li>Paper's own {@code Server#getTPS()} and {@code getAverageTickTime()}, reached
     *       reflectively through {@code :platform-bukkit-paper} — the only source that can report
     *       MSPT.
     *   <li>{@code MinecraftServer.recentTps} read out of the server internals, which covers every
     *       Spigot back to 1.8.
     *   <li>{@link TickSource#UNAVAILABLE}.
     * </ol>
     *
     * <p>The Paper implementation is loaded by name rather than referenced, because naming it here
     * would link a class whose method bodies call Paper-only API — and the JVM verifies those
     * bodies when the class is linked, not when a method is first called. A guarded
     * {@code Class.forName} is what keeps a plain Spigot from ever touching it.
     */
    public static TickSource tickSource(HeimdallLogger logger) {
        TickSource paper = loadPaperTickSource(logger);
        if (paper != null && paper.isAvailable()) {
            logger.debug(() -> "tick source: " + paper.describe());
            return paper;
        }
        TickSource nms = NmsTickSource.tryCreate();
        if (nms != null) {
            logger.debug(() -> "tick source: " + nms.describe());
            return nms;
        }
        logger.debug("tick source: unavailable — this server reports no tick rate");
        return TickSource.UNAVAILABLE;
    }

    private static TickSource loadPaperTickSource(HeimdallLogger logger) {
        try {
            Class<?> support = Class.forName("com.heimdall.platform.bukkit.paper.PaperSupport");
            Object usable = support.getMethod("hasTickApi").invoke(null);
            if (!Boolean.TRUE.equals(usable)) {
                return null;
            }
            Class<?> implementation =
                    Class.forName("com.heimdall.platform.bukkit.paper.PaperTickSource");
            return (TickSource) implementation.newInstance();
        } catch (Throwable notPaper) {
            logger.debug(() -> "no Paper tick API on this server: " + notPaper);
            return null;
        }
    }
}
