/**
 * Everything that talks HTTP to the bot: request signing, one request path, and the client that
 * exposes the six endpoints on top of them.
 *
 * <p>The WebSocket tunnel is not here — it arrives in phase 1b — but {@link
 * com.heimdall.core.http.HmacSigner} already signs its handshake, because the two transports sign
 * the path differently and that asymmetry deserves to live in one place.
 */
package com.heimdall.core.http;
