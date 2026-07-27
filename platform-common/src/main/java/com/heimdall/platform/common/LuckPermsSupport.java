package com.heimdall.platform.common;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.LuckPermsBridge;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * The probe that decides whether {@link LuckPermsIntegration} may be loaded at all.
 *
 * <p><strong>This class deliberately names no LuckPerms type.</strong> That is the whole reason it
 * is separate from the integration it creates. A JVM links a class by verifying its methods, and
 * verifying a method that assigns {@code LuckPermsProvider.get()} to a {@code LuckPerms} local
 * requires loading both of those types — so a class that mentions them cannot even be <em>touched</em>
 * on a server without LuckPerms installed, however carefully its own code checks first. The check
 * has to live somewhere the verifier will never follow, which means somewhere with no LuckPerms in
 * its constant pool.
 *
 * <p>{@link #resolve} is cheap and is called on every {@code luckPerms()} lookup rather than once at
 * boot: there is no load-order guarantee between plugins, and an integration resolved once at
 * construction can cache a permanent failure on a server where LuckPerms simply started second.
 * That is issue #796 / MC-10 — v2's Bukkit implementation did exactly that and role sync stayed
 * dead for the life of the process.
 */
public final class LuckPermsSupport {

    private static final String PROBE_CLASS = "net.luckperms.api.LuckPerms";

    private LuckPermsSupport() {
    }

    /** Whether the LuckPerms API is on the classpath. Says nothing about whether it has started. */
    public static boolean isPresent() {
        try {
            Class.forName(PROBE_CLASS);
            return true;
        } catch (Throwable absent) {
            return false;
        }
    }

    /**
     * A bridge onto the running LuckPerms, if there is one.
     *
     * <p>Returns empty both when LuckPerms is absent and when it is present but has not registered
     * its service yet. Those are the same answer to the caller — "no role sync right now" — and the
     * next call asks again.
     *
     * @param executor where the bridge's blocking work runs; {@code heimdall-io} in production
     */
    public static Optional<LuckPermsBridge> resolve(HeimdallLogger logger, Executor executor) {
        if (!isPresent()) {
            return Optional.empty();
        }
        try {
            LuckPermsIntegration integration = LuckPermsIntegration.tryCreate(logger, executor);
            return integration == null
                    ? Optional.<LuckPermsBridge>empty()
                    : Optional.<LuckPermsBridge>of(integration);
        } catch (Throwable broken) {
            // A LuckPerms whose API shape we cannot use is a missing LuckPerms as far as everything
            // above is concerned. Debug rather than warn: this is asked on every lookup, and a
            // warning per lookup would be its own outage.
            logger.debug("LuckPerms is installed but its API could not be used: " + broken);
            return Optional.empty();
        }
    }
}
