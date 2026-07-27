package com.heimdall.core.tunnel;

import com.heimdall.core.json.Payload;

/**
 * Supplies the periodic health snapshot sent on the heartbeat tick.
 *
 * <p>The shape the bot stores is {@code {tps, mspt, onlinePlayers, maxPlayers, usedMemMb,
 * maxMemMb}}, all optional — a proxy has no TPS, and an old Bukkit has no MSPT. Every field being
 * optional is why this returns a {@link Payload} rather than a typed record: a platform that can
 * only answer half the questions should send half the fields, not zeroes that the dashboard would
 * then chart as a server running at 0 TPS.
 *
 * <p><strong>Health doubles as a liveness signal.</strong> The bot's sweep refreshes a connection's
 * last-seen on {@code pong} <em>and</em> on {@code health}, so a heartbeat that carries health is
 * keeping the connection alive whether or not a pong went with it.
 *
 * <p>Implementations are called on {@code heimdall-ws} and must not block — a snapshot that waits
 * on the main server thread delays the timeout check that runs immediately before it. Throwing is
 * tolerated: the tick logs it and skips the health message.
 *
 * <p>Optional. With no source registered the client sends no {@code health} at all, which is a
 * legitimate configuration — v2 behaved the same way before a platform set its supplier.
 */
public interface HealthSnapshotSource {

    /** The current snapshot, or {@code null} to send nothing this tick. */
    Payload snapshot();
}
