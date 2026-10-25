package com.heimdall.module.punishments;

import java.util.function.Consumer;

/**
 * Asks whether the server a frozen player was kicked from still answers, so a logout ban is not
 * fired at someone whose server went down under them. A proxy supplies one with each kick it
 * reports; see {@link HeimdallPunishmentsModule#noteKicked(java.util.UUID, ServerProbe)}.
 */
public interface ServerProbe {

    /**
     * Pings the server and answers once, on any thread: {@code true} when it replied. Must answer
     * even when the ping fails, with {@code false}, and must not block the caller.
     */
    void reachable(Consumer<Boolean> answer);
}
