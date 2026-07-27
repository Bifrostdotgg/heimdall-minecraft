/**
 * The outbound WebSocket tunnel to the bot.
 *
 * <p>A server connects out; the plugin never listens. Everything the bot needs to ask of a
 * Minecraft server — run this command, who is online, apply these groups, are you alive — travels
 * over that one connection, multiplexed by a {@code {id, type, payload}} envelope and correlated by
 * echoed id.
 *
 * <h2>What is here</h2>
 *
 * <ul>
 *   <li>{@link com.heimdall.core.tunnel.TunnelClient} — the connection: reconnect, heartbeat,
 *       correlation, negotiation. Its class javadoc lists the six production invariants it holds.
 *   <li>{@link com.heimdall.core.tunnel.TunnelBus} — what a feature module sees of it.
 *   <li>{@link com.heimdall.core.tunnel.TunnelSocket} and friends — the seam that keeps the
 *       WebSocket library swappable and lets tests make a socket misbehave on demand.
 *   <li>{@link com.heimdall.core.tunnel.Capabilities} — the capability identifiers, in one place,
 *       because they are a wire contract and a typo in one produces a module that silently receives
 *       no configuration.
 * </ul>
 *
 * <h2>The one thing most likely to be got wrong</h2>
 *
 * <p>The WebSocket upgrade's HMAC signs the path <strong>without</strong> its query string, even
 * though the signature travels in that query string; the HTTP API signs the path <strong>with</strong>
 * it. That asymmetry is the bot's real behaviour, it is invisible until a connection is refused with
 * nothing in either log to say why, and it is expressed exactly once — in
 * {@code TunnelUrls.upgradeUrl}, via {@code HmacSigner.forWsHandshake}.
 */
package com.heimdall.core.tunnel;
