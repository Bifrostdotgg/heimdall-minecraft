package com.heimdall.core.platform;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.log.HeimdallLogger;

/**
 * Answers the question {@link ServerRole#AUTO} asks: what is this instance, in this network?
 *
 * <p>Two signals, supplied by the platform, and one piece of policy, which lives here so it is the
 * same on every platform and can be tested without a server:
 *
 * <ul>
 *   <li>a configured role that is not {@code AUTO} always wins — an operator who wrote it down has
 *       made a decision, and a detector that overruled them would be undebuggable;
 *   <li>otherwise, a proxy is a {@link ServerRole#GATEKEEPER};
 *   <li>otherwise, a server that has been told to trust a proxy's forwarded identity is an
 *       {@link ServerRole#ENFORCER};
 *   <li>otherwise, {@link ServerRole#STANDALONE}.
 * </ul>
 *
 * <h2>Why "behind a proxy" is read from the server's own config</h2>
 *
 * <p>The signal that matters is not "is a proxy connected right now" — nothing is connected while
 * the plugin is enabling — it is <strong>"has this server been configured to accept a proxy's
 * word about who a player is"</strong>. On the Bukkit family that is {@code settings.bungeecord}
 * in {@code spigot.yml} or Velocity forwarding in Paper's config, and both are exactly the switch
 * an operator flips when they put a proxy in front of a server.
 *
 * <p>That framing also makes the failure mode safe. A server misconfigured as {@code STANDALONE}
 * behind a proxy re-runs the login decision the proxy already made, which is redundant but not
 * wrong. A server misconfigured as {@code ENFORCER} with no proxy would enforce nothing at all —
 * so the detector only ever reports {@code ENFORCER} on the evidence of a deliberate config change.
 *
 * <p>Implementations are called once during enable and must not block.
 */
public interface InstanceRoleDetector {

    /** Whether this instance <em>is</em> the proxy. */
    boolean isProxy();

    /**
     * Whether this instance is configured to accept a proxy's forwarded player identity.
     *
     * <p>Meaningless on a proxy, which is why {@link #resolve} never asks once {@link #isProxy()}
     * has said yes.
     */
    boolean isBehindProxy();

    /**
     * Applies the policy above and logs the outcome once.
     *
     * @param configured the role from {@code bootstrap.yml}; {@code null} is treated as
     *     {@link ServerRole#AUTO}
     * @param detector the platform's signals; {@code null} resolves to {@link ServerRole#STANDALONE}
     * @param logger where the one-line explanation goes; {@code null} suppresses it
     * @return a resolved role — never {@link ServerRole#AUTO}
     */
    static ServerRole resolve(
            ServerRole configured, InstanceRoleDetector detector, HeimdallLogger logger) {
        if (configured != null && configured != ServerRole.AUTO) {
            if (logger != null) {
                logger.info("server role: " + configured.wireName() + " (set in bootstrap.yml)");
            }
            return configured;
        }
        if (detector == null) {
            if (logger != null) {
                logger.info("server role: " + ServerRole.STANDALONE.wireName()
                        + " (auto — no platform detector available)");
            }
            return ServerRole.STANDALONE;
        }

        ServerRole resolved;
        String because;
        if (detector.isProxy()) {
            resolved = ServerRole.GATEKEEPER;
            because = "this instance is the proxy";
        } else if (detector.isBehindProxy()) {
            resolved = ServerRole.ENFORCER;
            because = "proxy forwarding is enabled in this server's own config";
        } else {
            resolved = ServerRole.STANDALONE;
            because = "no proxy forwarding is configured";
        }
        if (logger != null) {
            logger.info("server role: " + resolved.wireName() + " (auto — " + because + ")");
        }
        return resolved;
    }
}
