/**
 * The local mirror: a disk-backed, expiry-bounded copy of state the bot owns.
 *
 * <p>Generalised from v2's {@code WhitelistCache}, which was a whitelist-specific class with the
 * revocation bound (issue #771) baked into it. The bound is the interesting part and it is not
 * whitelist-specific, so it lives on {@link com.heimdall.core.mirror.MirrorStore} and any module
 * that needs to survive a bot outage can have one.
 */
package com.heimdall.core.mirror;
