package com.heimdall.shell.contract;

import java.util.UUID;

/**
 * The running core's punishment mirror, as the shell's permanent {@code HeimdallPunishments} sees
 * it.
 *
 * <p>The same arrangement as {@link TunnelBackend}, and for the same reason: other plugins hold the
 * shell's object, which forwards to whichever backend the current core has bound, so a hot swap
 * never leaves them asking a stopped core or pinning its classloader. With no backend bound (between
 * generations, or with no core at all) the shell answers "not punished" itself.
 *
 * <h2>Contract</h2>
 *
 * <p>Both methods are called from any thread, including Paper's async chat threads and the main
 * thread, on every chat line a consumer checks. They must read memory only: no network, no disk,
 * no lock a server thread could hold. They must not throw.
 */
public interface PunishmentBackend {

    /** Whether the punishments module is enabled in this generation. */
    boolean isAvailable();

    /**
     * The active, unexpired punishment of {@code type} on {@code player}, or {@code null}.
     *
     * @param player never {@code null}; the shell filters that
     * @param type {@code ban}, {@code mute} or {@code freeze}; never {@code null}
     */
    PunishmentRecord active(UUID player, String type);
}
