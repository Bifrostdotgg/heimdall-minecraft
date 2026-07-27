/**
 * Join and quit, as notifications the platform pushes into core.
 *
 * <p>The other direction from {@code com.heimdall.core.platform}: that package is the questions core
 * asks the server, this one is the server telling core something happened. Keeping them apart is
 * why {@code PlatformFacade} did not have to grow a listener registry alongside its accessors.
 *
 * <p>Deliberately not a third {@code Pipeline}. A pipeline arbitrates a decision — allow, deny,
 * abstain, ordered by priority, first denial wins. A join has no decision left to arbitrate: the
 * player is already on the server. Modelling it as a pipeline would invite an interceptor to "deny"
 * a quit, and the whole vocabulary would be wrong. See departure S1.
 */
package com.heimdall.core.session;
