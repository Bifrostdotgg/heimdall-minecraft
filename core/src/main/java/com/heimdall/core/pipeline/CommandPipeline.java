package com.heimdall.core.pipeline;

import com.heimdall.core.log.HeimdallLogger;

/**
 * Commands a player typed: the checks that can cancel one.
 *
 * <p>The default is allow. No interceptors means no command moderation, and a server with none
 * lets people run commands. Fail-open on a throwing interceptor is {@link Pipeline}'s job.
 *
 * <p>Wired only on the Bukkit family. Proxies must not cancel player commands or chat: signed
 * chat from 1.19.1 disconnects the client. Mute enforcement lives on ENFORCER and STANDALONE.
 */
public final class CommandPipeline extends Pipeline<CommandAttempt> {

    public CommandPipeline(HeimdallLogger logger) {
        super("command", logger, Verdict.Decision.ALLOW);
    }
}
