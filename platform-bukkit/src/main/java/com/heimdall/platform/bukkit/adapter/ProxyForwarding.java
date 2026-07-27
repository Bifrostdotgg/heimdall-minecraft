package com.heimdall.platform.bukkit.adapter;

import com.heimdall.core.config.YamlProbe;
import com.heimdall.core.log.HeimdallLogger;
import java.io.File;
import java.nio.file.Path;

/**
 * Whether this server has been told to accept a proxy's word about who a player is.
 *
 * <p>That is the signal role detection needs, and it is deliberately read from the server's
 * <em>own</em> configuration files rather than from an API. Three reasons:
 *
 * <ul>
 *   <li><strong>The keys have outlived every API that exposed them.</strong> {@code
 *       settings.bungeecord} has been in {@code spigot.yml} since 2013. Paper's velocity switch has
 *       moved class three times — {@code PaperConfig.velocitySupport} in 1.16, gone in 1.19,
 *       {@code GlobalConfiguration} after that — while the YAML key it is loaded from has changed
 *       exactly once, when the file was renamed.
 *   <li><strong>Reading a file needs no version guard.</strong> An API-based check would need a
 *       reflective probe per Paper generation, each of which is a place to be silently wrong.
 *   <li><strong>It is testable.</strong> A fixture directory with a {@code spigot.yml} in it
 *       exercises the real code path, which no amount of mocking a static config class does.
 * </ul>
 *
 * <p>Parsing goes through {@link YamlProbe} — Heimdall's own relocated SnakeYAML — rather than
 * Bukkit's {@code YamlConfiguration}, so the answer cannot depend on which SnakeYAML the server
 * happens to bundle. That no {@code org.bukkit} import is left in this file is a consequence rather
 * than a goal, but it is why the whole thing can be tested against a temporary directory.
 *
 * <p>A missing or unreadable file means "not configured", which is the safe answer: a server
 * wrongly treated as standalone re-runs a login decision the proxy already made, which is redundant
 * rather than wrong, while a server wrongly treated as an enforcer would enforce nothing at all.
 */
public final class ProxyForwarding {

    /** BungeeCord/Velocity legacy forwarding, in {@code spigot.yml}. Unchanged since 2013. */
    private static final String SPIGOT_FILE = "spigot.yml";
    private static final String SPIGOT_KEY = "settings.bungeecord";

    /** Paper's modern-forwarding switch, in {@code paper.yml} up to 1.18. */
    private static final String PAPER_LEGACY_FILE = "paper.yml";
    private static final String PAPER_LEGACY_KEY = "settings.velocity-support.enabled";

    /** The same switch after Paper split its configuration, 1.19 onwards. */
    private static final String PAPER_GLOBAL_FILE = "config/paper-global.yml";
    private static final String PAPER_GLOBAL_KEY = "proxies.velocity.enabled";

    private ProxyForwarding() {
    }

    /**
     * Whether any form of proxy forwarding is switched on.
     *
     * @param serverDirectory the server's working directory — {@code Bukkit.getWorldContainer()} in
     *     production, a fixture directory in a test
     */
    public static boolean isEnabled(File serverDirectory, HeimdallLogger logger) {
        if (serverDirectory == null) {
            return false;
        }
        Path root = serverDirectory.toPath();
        return read(root, SPIGOT_FILE, SPIGOT_KEY, logger)
                || read(root, PAPER_LEGACY_FILE, PAPER_LEGACY_KEY, logger)
                || read(root, PAPER_GLOBAL_FILE, PAPER_GLOBAL_KEY, logger);
    }

    private static boolean read(Path root, String fileName, String key, HeimdallLogger logger) {
        boolean enabled = YamlProbe.flag(root.resolve(fileName), key, false, logger);
        if (enabled && logger != null) {
            logger.debug(() -> "proxy forwarding detected: " + key + " in " + fileName);
        }
        return enabled;
    }
}
