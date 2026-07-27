package com.heimdall.core.tunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A bare-socket WebSocket server that can refuse, or accept and then say nothing.
 *
 * <p>Two cases the {@code stub-bot} fixture deliberately cannot produce, both of which matter:
 *
 * <ul>
 *   <li><strong>Refusing.</strong> A bot that is redeploying rejects connections for a while and
 *       then starts accepting. That is the reconnect storm, and it is where a backoff that does not
 *       reset or a socket that leaks per attempt actually shows up.
 *   <li><strong>Silence after a successful upgrade.</strong> This is what a <em>v2</em> bot looks
 *       like to a v3 client: the connection is fine and {@code identify} is simply never answered.
 *       {@code stub-bot} always speaks v3 to a v3 client, so it cannot be made to do this.
 * </ul>
 *
 * <p>Written against {@link ServerSocket} rather than a WebSocket library because the whole point is
 * to misbehave, and a library's job is to stop you. The handshake it performs is the real one — the
 * client's own library validates {@code Sec-WebSocket-Accept} and would fail the connection
 * otherwise — but nothing beyond it is implemented, because nothing beyond it is needed.
 */
final class FlakyWebSocketServer implements AutoCloseable {

    /** RFC 6455's magic string, appended to the client key before hashing. */
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket serverSocket;
    private final Thread acceptor;
    private final AtomicInteger refusalsRemaining = new AtomicInteger();
    private final AtomicInteger acceptedConnections = new AtomicInteger();
    private final AtomicInteger totalConnections = new AtomicInteger();
    private final List<Socket> open = Collections.synchronizedList(new ArrayList<Socket>());

    private volatile boolean running = true;

    FlakyWebSocketServer() throws IOException {
        this.serverSocket = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        this.acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "flaky-ws-acceptor");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    /** The {@code http://…} base URL to point a {@link TunnelSettings} at. */
    String baseUrl() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort();
    }

    /** The next {@code count} connections are dropped without a handshake. */
    FlakyWebSocketServer refuseNext(int count) {
        refusalsRemaining.set(count);
        return this;
    }

    /** How many connections completed the WebSocket handshake. */
    int acceptedConnections() {
        return acceptedConnections.get();
    }

    /** How many TCP connections arrived at all, refused or not. */
    int totalConnections() {
        return totalConnections.get();
    }

    @Override
    public void close() {
        running = false;
        synchronized (open) {
            for (Socket socket : open) {
                closeQuietly(socket);
            }
            open.clear();
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Closing a listening socket to stop the accept loop; failing here changes nothing.
        }
        acceptor.interrupt();
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                return;
            }
            totalConnections.incrementAndGet();
            if (refusalsRemaining.getAndUpdate(new java.util.function.IntUnaryOperator() {
                @Override
                public int applyAsInt(int current) {
                    return current > 0 ? current - 1 : 0;
                }
            }) > 0) {
                closeQuietly(socket);
                continue;
            }
            try {
                upgrade(socket);
                open.add(socket);
                acceptedConnections.incrementAndGet();
            } catch (IOException e) {
                closeQuietly(socket);
            }
        }
    }

    /** Performs the real handshake, then leaves the socket open and silent forever. */
    private void upgrade(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        int read;
        while ((read = in.read()) != -1) {
            head.append((char) read);
            if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) {
                break;
            }
        }
        String key = header(head.toString(), "sec-websocket-key");
        if (key == null) {
            throw new IOException("no Sec-WebSocket-Key in the request");
        }
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept(key) + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String header(String requestHead, String name) {
        for (String line : requestHead.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT)
                    .equals(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static String accept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Deliberately silent: this fixture's whole job is connections that end badly.
        }
    }
}
