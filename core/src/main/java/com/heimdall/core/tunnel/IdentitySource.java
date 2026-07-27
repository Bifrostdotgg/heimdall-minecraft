package com.heimdall.core.tunnel;

/**
 * Supplies the {@link ServerIdentity} sent on every connect.
 *
 * <p>An interface rather than a field on {@link TunnelSettings} because the values come from the
 * running server — its software, its Minecraft version, the role that "auto" resolved to — and core
 * is platform-free by construction. The platform module implements this in phase 1c; core's tests
 * use a fixed one.
 *
 * <p>Asked again on <em>every</em> connect, not cached. A reconnect after a proxy came online is
 * exactly when a resolved role can legitimately have changed, and re-sending stale metadata would
 * leave the dashboard describing a server that no longer exists.
 *
 * <p>Implementations must be thread-safe (called from the socket's reading thread on open) and must
 * not block. Throwing is tolerated — the client logs it and connects without identity metadata
 * rather than failing the connection — but it means an unidentified server on the dashboard.
 */
public interface IdentitySource {

    /** This server's identity, right now. Must not return {@code null}. */
    ServerIdentity identity();
}
