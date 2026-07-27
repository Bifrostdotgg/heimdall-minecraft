package com.heimdall.core.pipeline;

import com.heimdall.core.log.HeimdallLogger;

/**
 * The login gate: every check that can keep a player out, in order.
 *
 * <h2>The default is allow, and that is a pipeline-level statement only</h2>
 *
 * <p>An all-abstain run admits the player. That is not a policy about whitelisting — it is what
 * "nothing here has an opinion" has to mean. The pipeline with no interceptors registered is the
 * pipeline on a server where the whitelist module is switched off, and a server with the whitelist
 * off must let people in.
 *
 * <p><strong>Enforcement strictness lives in the interceptors, not here.</strong> "The bot is
 * unreachable — do we admit or refuse?" is a question about the whitelist check specifically, it has
 * a safe default that a server owner can change, and it arrives with that module in phase 1d. An
 * interceptor that cannot reach the bot returns an explicit allow or an explicit deny; it does not
 * abstain and leave the answer to a default that knows nothing about the situation.
 */
public final class LoginPipeline extends Pipeline<LoginAttempt> {

    public LoginPipeline(HeimdallLogger logger) {
        super("login", logger, Verdict.Decision.ALLOW);
    }
}
