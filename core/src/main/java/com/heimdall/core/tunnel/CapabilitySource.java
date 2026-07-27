package com.heimdall.core.tunnel;

import java.util.Set;

/**
 * Supplies the capability set declared in {@code identify}.
 *
 * <p>In production this is {@code ModuleManager}: the declared set is the union of what the
 * currently enabled modules claim. That direction matters — capabilities describe what this jar
 * will actually <em>do</em> right now, not what it could do if configured differently. The bot
 * narrows its config push to the declared set, so claiming a capability for a disabled module means
 * receiving settings nothing will read.
 *
 * <p>The wiring is a setter on {@link TunnelClient} rather than a constructor argument because the
 * dependency genuinely runs both ways: {@code ModuleManager} hands each module a bus backed by the
 * client, and the client asks {@code ModuleManager} what to declare. Setter injection makes that
 * cycle explicit and breakable instead of forcing one of them into a factory.
 *
 * <p>Called on the socket's reading thread during the handshake; must be fast and thread-safe.
 */
public interface CapabilitySource {

    /** The capability identifiers to declare. Never {@code null}; may be empty. */
    Set<String> capabilities();
}
