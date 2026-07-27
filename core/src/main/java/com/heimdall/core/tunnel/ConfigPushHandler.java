package com.heimdall.core.tunnel;

import com.heimdall.core.json.Payload;

/**
 * Receives {@code config.push} documents from the tunnel.
 *
 * <p>Implemented by {@code RemoteConfig}. The interface exists so the dependency runs one way only:
 * {@code remoteconfig} knows about the tunnel, the tunnel knows about this one method. A tunnel
 * that imported {@code RemoteConfig} directly would make either package impossible to test without
 * the other.
 *
 * <p><strong>Acknowledgement is not this handler's job.</strong> The tunnel sends
 * {@code config.ack} with the pushed version whatever the handler decides — including for a stale
 * or replayed push the handler refuses to apply. Silence would leave the bot believing the push was
 * lost and re-sending it forever.
 *
 * <p>Called on the socket's reading thread. Must not block.
 */
public interface ConfigPushHandler {

    /**
     * Applies a pushed configuration document.
     *
     * @param document the raw {@code {version, modules, messages?}} payload
     */
    void onConfigPush(Payload document);
}
