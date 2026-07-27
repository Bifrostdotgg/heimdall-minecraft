package com.heimdall.core.tunnel;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFrame;
import java.util.List;
import java.util.Map;

/**
 * Adapts one nv-websocket-client socket to {@link TunnelSocket}.
 *
 * <p>Deliberately thin. The only judgement it makes is collapsing the library's several failure
 * callbacks onto one {@code onError}, and mapping "abort" onto the disconnect overload that does
 * not wait for the peer.
 *
 * <p>Threading: callbacks arrive on the library's reading thread and are passed straight through.
 */
final class NvTunnelSocket implements TunnelSocket {

    /**
     * The close code sent when we abandon a connection we believe is dead.
     *
     * <p>1001 "going away" rather than 1000 "normal": the link is being dropped because it stopped
     * working, and a bot-side log saying "normal closure" for a heartbeat timeout would be a lie
     * that costs someone an afternoon.
     */
    private static final int GOING_AWAY = 1001;

    /**
     * Milliseconds to wait for the peer's close frame during {@link #abort()}.
     *
     * <p>Zero. That is the entire point of abort — see {@link TunnelSocket#abort()}.
     */
    private static final long NO_CLOSE_WAIT = 0L;

    private final WebSocket socket;

    NvTunnelSocket(WebSocket socket, final TunnelSocketListener listener) {
        this.socket = socket;
        socket.addListener(new WebSocketAdapter() {

            @Override
            public void onConnected(WebSocket ws, Map<String, List<String>> headers) {
                listener.onOpen();
            }

            @Override
            public void onTextMessage(WebSocket ws, String message) {
                listener.onText(message);
            }

            @Override
            public void onConnectError(WebSocket ws, WebSocketException cause) {
                listener.onError(cause);
            }

            @Override
            public void onError(WebSocket ws, WebSocketException cause) {
                listener.onError(cause);
            }

            /**
             * A protocol violation or an unhandled listener exception. Routed to the same place as
             * a transport error: from the tunnel's point of view the connection is unusable either
             * way, and the recovery is identical.
             */
            @Override
            public void onUnexpectedError(WebSocket ws, WebSocketException cause) {
                listener.onError(cause);
            }

            @Override
            public void onDisconnected(
                    WebSocket ws,
                    WebSocketFrame serverCloseFrame,
                    WebSocketFrame clientCloseFrame,
                    boolean closedByServer) {
                // Whichever side closed, prefer that side's frame for the code and reason: it is
                // the one carrying why. A connection that died without either — the black-holed
                // case — reports 0 and "", which is exactly the "we never heard back" signal.
                WebSocketFrame frame = closedByServer ? serverCloseFrame : clientCloseFrame;
                listener.onClose(closeCode(frame), closeReason(frame));
            }
        });
    }

    @Override
    public void connect() {
        socket.connectAsynchronously();
    }

    @Override
    public void sendText(String text) {
        if (text == null) {
            return;
        }
        // isOpen() rather than a try/catch: nv queues frames on a socket that is still connecting
        // and throws on one that is closed, and neither is worth a stack trace on the login path.
        if (socket.isOpen()) {
            socket.sendText(text);
        }
    }

    @Override
    public void abort() {
        socket.disconnect(GOING_AWAY, "Heimdall aborting a dead connection", NO_CLOSE_WAIT);
    }

    @Override
    public void close(int code, String reason) {
        socket.disconnect(code, reason);
    }

    @Override
    public boolean isOpen() {
        return socket.isOpen();
    }

    private static int closeCode(WebSocketFrame frame) {
        return frame == null ? 0 : frame.getCloseCode();
    }

    private static String closeReason(WebSocketFrame frame) {
        if (frame == null || frame.getCloseReason() == null) {
            return "";
        }
        return frame.getCloseReason();
    }
}
