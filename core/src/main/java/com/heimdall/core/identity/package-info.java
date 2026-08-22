/**
 * Which machine this install's credentials belong to.
 *
 * <p>The bot mints a {@code serverId} once, at claim time, and it lives in {@code bootstrap.yml}
 * next to the token. Copy that directory and the copy is, as far as the bot can tell, the same
 * server: both dial the tunnel with the same id, the bot keeps the newest socket, and the two
 * copies evict each other for as long as they are both running.
 *
 * <p>So the plugin records a fingerprint of the instance it was bound to, and compares it on every
 * boot. See {@link com.heimdall.core.identity.InstanceFingerprint} for what goes into one, and
 * {@link com.heimdall.core.wiring.IdentityGuard} for what happens when it does not match.
 */
package com.heimdall.core.identity;
