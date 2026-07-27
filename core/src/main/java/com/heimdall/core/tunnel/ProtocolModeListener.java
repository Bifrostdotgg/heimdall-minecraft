package com.heimdall.core.tunnel;

/**
 * Notified when the negotiated {@link ProtocolMode} changes.
 *
 * <p>Fired on {@code heimdall-ws} or on the socket's reading thread, depending on which of the two
 * outcomes happened — the ack arriving, or the deadline expiring. Listeners must therefore be quick
 * and must not block; anything substantial belongs on an executor the listener owns.
 *
 * <p>Only fired on an actual change, so a listener does not have to de-duplicate.
 */
public interface ProtocolModeListener {

    /**
     * @param previous what the mode was
     * @param current what it is now; never equal to {@code previous}
     */
    void onModeChanged(ProtocolMode previous, ProtocolMode current);
}
