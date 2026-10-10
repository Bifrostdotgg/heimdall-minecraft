package com.heimdall.core.testing;

import com.heimdall.core.update.DownloadPolicy;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A loopback HTTP server that serves one fixed body and counts the requests for it, for the
 * platform update installers' tests: what an installer does after a download is refused can only be
 * observed against a transfer that really happened (departure D87).
 */
public final class JarServer implements AutoCloseable {

    private final HttpServer server;
    private final byte[] body;
    private final AtomicInteger requests = new AtomicInteger();

    public JarServer(final byte[] body) {
        this.body = body.clone();
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, JarServer.this.body.length);
            OutputStream out = exchange.getResponseBody();
            try {
                out.write(JarServer.this.body);
            } finally {
                out.close();
            }
        });
        server.start();
    }

    /** A URL on this server. */
    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** How many requests this server has answered. */
    public int requests() {
        return requests.get();
    }

    /** A test policy that allows this server, unpinned and over plain HTTP. */
    public static DownloadPolicy loopbackPolicy() {
        return DownloadPolicy.builder()
                .allowedHosts("127.0.0.1")
                .requireHttps(false)
                .maxBytes(DownloadPolicy.MAX_DOWNLOAD_BYTES)
                .connectTimeoutMs(5000)
                .readTimeoutMs(5000)
                .build();
    }

    /** Lowercase hex SHA-256 of {@code bytes}. */
    public static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
