package com.heimdall.stubbot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves plain HTTP and WebSocket upgrades on a single public port by peeking at each connection's
 * request head and relaying it to whichever loopback server should handle it.
 *
 * <p>This exists for one reason: <strong>the real bot answers both on the same port.</strong> The
 * plugin derives its WebSocket URL from {@code api.baseUrl} by swapping the scheme — same host, same
 * port. A fixture that needed two ports would force phase 1 to invent a second config key that only
 * ever exists for tests, and a config shape the production code never exercises is exactly the kind
 * of thing that works in CI and fails on a customer's server.
 *
 * <p>The JDK's {@code HttpServer} cannot hand a socket over for an upgrade and Java-WebSocket cannot
 * serve ordinary HTTP, so something has to sit in front. ~100 lines of byte relay is the cheapest
 * honest answer.
 */
final class PortMultiplexer implements AutoCloseable {

    /** Enough for any request head we could need to classify; a larger one is not a real client. */
    private static final int MAX_HEAD_BYTES = 16 * 1024;

    private static final long HEAD_READ_TIMEOUT_MS = 10_000L;

    private final ServerSocket serverSocket;
    private final int httpPort;
    private final int wsPort;
    private final ExecutorService pool;
    private final Thread acceptThread;
    private volatile boolean running = true;

    /**
     * Decides whether a WebSocket upgrade may proceed, and with what HTTP status if not.
     *
     * <p>It lives here rather than in the WebSocket server because that is where the bot's lives:
     * it inspects the raw upgrade request and writes an HTTP response on the socket <em>before</em>
     * handing it to the WebSocket library. A library-level rejection cannot do that — it produces a
     * close frame or a generic 400 — and the distinction is the whole point of these two statuses.
     * A plugin has to be able to tell "this serverId belongs to another token" (403, permanent, stop
     * retrying) from "the registry is unreadable right now" (503, transient, back off and retry).
     */
    private volatile UpgradeGate upgradeGate = head -> 0;

    PortMultiplexer(String bindHost, int port, int httpPort, int wsPort) {
        this.httpPort = httpPort;
        this.wsPort = wsPort;
        AtomicInteger threadNumber = new AtomicInteger();
        this.pool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "stub-bot-mux-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            this.serverSocket = new ServerSocket();
            this.serverSocket.setReuseAddress(true);
            this.serverSocket.bind(new InetSocketAddress(bindHost, port), 64);
        } catch (IOException e) {
            throw new IllegalStateException("could not bind the stub port " + bindHost + ":" + port, e);
        }
        this.acceptThread = new Thread(this::acceptLoop, "stub-bot-accept");
        this.acceptThread.setDaemon(true);
    }

    /** Returns 0 to allow the upgrade, or the HTTP status to reject it with. */
    interface UpgradeGate {
        int rejectionStatus(String requestHead);
    }

    void setUpgradeGate(UpgradeGate gate) {
        this.upgradeGate = gate == null ? head -> 0 : gate;
    }

    void start() {
        acceptThread.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                pool.execute(() -> route(client));
            } catch (IOException e) {
                if (running) {
                    StubLog.warn("accept failed: " + e);
                }
                return;
            }
        }
    }

    private void route(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout((int) HEAD_READ_TIMEOUT_MS);
            byte[] head = readHead(client.getInputStream());
            if (head.length == 0) {
                client.close();
                return;
            }
            client.setSoTimeout(0);

            String requestHead = new String(head, StandardCharsets.ISO_8859_1);
            boolean websocket = looksLikeWebSocketUpgrade(requestHead);

            if (websocket) {
                int rejection = upgradeGate.rejectionStatus(requestHead);
                if (rejection != 0) {
                    writeRejection(client, rejection);
                    closeQuietly(client);
                    return;
                }
            }
            int target = websocket ? wsPort : httpPort;

            upstream = new Socket(InetAddress.getLoopbackAddress(), target);
            OutputStream toUpstream = upstream.getOutputStream();
            toUpstream.write(head);
            toUpstream.flush();

            Socket finalUpstream = upstream;
            pool.execute(() -> pump(finalUpstream, client));
            pump(client, upstream);
        } catch (IOException e) {
            StubLog.debug("connection closed during routing: " + e);
            closeQuietly(upstream);
            closeQuietly(client);
        }
    }

    /**
     * Reads up to the end of the request head ({@code \r\n\r\n}).
     *
     * <p>Read one byte at a time on purpose. Anything larger risks pulling in the first bytes of a
     * WebSocket frame the client pipelined after its handshake — which would then have to be
     * buffered and re-injected, for no gain on a fixture that handles a handful of connections.
     */
    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
        int b0 = -1;
        int b1 = -1;
        int b2 = -1;
        int b3 = -1;
        while (buffer.size() < MAX_HEAD_BYTES) {
            int read = in.read();
            if (read < 0) {
                return buffer.toByteArray();
            }
            buffer.write(read);
            b0 = b1;
            b1 = b2;
            b2 = b3;
            b3 = read;
            if (b0 == '\r' && b1 == '\n' && b2 == '\r' && b3 == '\n') {
                return buffer.toByteArray();
            }
        }
        return buffer.toByteArray();
    }

    /**
     * Classifies by the {@code Upgrade} header rather than by path.
     *
     * <p>Path-sniffing would look simpler, but it answers the wrong question: a plain GET to
     * {@code /ws/minecraft/…} is not an upgrade and should get an HTTP response, and a future
     * upgrade on some other path should still reach the WebSocket server.
     */
    static boolean looksLikeWebSocketUpgrade(String head) {
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if ("upgrade".equals(name)) {
                return line.substring(colon + 1).toLowerCase(Locale.ROOT).contains("websocket");
            }
        }
        return false;
    }

    private static void pump(Socket from, Socket to) {
        byte[] buffer = new byte[8192];
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            // Either end closing is the normal way a relayed connection ends.
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    /**
     * Answers a refused upgrade with a real HTTP status, byte for byte as the bot does.
     *
     * <p>No body, and {@code Connection: close}: an upgrade that is not going to happen should cost
     * the client one round trip and tell it exactly why in the status line.
     */
    private static void writeRejection(Socket client, int status) {
        String reason = status == 403 ? "Forbidden"
                : status == 503 ? "Service Unavailable"
                : "Bad Request";
        String response = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Connection: close\r\n"
                + "Content-Length: 0\r\n\r\n";
        try {
            client.getOutputStream().write(response.getBytes(StandardCharsets.ISO_8859_1));
            client.getOutputStream().flush();
        } catch (IOException e) {
            StubLog.debug("could not write the upgrade rejection: " + e);
        }
        StubLog.warn("refused a WebSocket upgrade with " + status + " " + reason);
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException e) {
            // Nothing useful to do.
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException e) {
            // Already closed.
        }
        pool.shutdownNow();
    }
}
