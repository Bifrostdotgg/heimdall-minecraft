package com.heimdall.core.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.Headers;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A loopback HTTP server that records what it was sent and answers with a script.
 *
 * <p>{@code :stub-bot} proves the plugin understands what the bot says. This proves the inverse —
 * what the plugin <em>sends</em> — which the stub cannot show, because it never echoes a request
 * body back. It is also the only way to assert on the retry loop, since that needs a server that
 * can be told to fail a fixed number of times and then counted.
 */
final class RecordingHttpServer implements AutoCloseable {

    /** One request as it arrived. */
    static final class Request {

        final String method;
        final String path;
        final String body;
        final Headers headers;

        Request(String method, String path, String body, Headers headers) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.headers = headers;
        }

        String header(String name) {
            return headers.getFirst(name);
        }
    }

    private final HttpServer server;
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<Request>());
    private final AtomicInteger served = new AtomicInteger();

    private volatile int status = 200;
    private volatile String responseBody = "{\"success\":true,\"data\":{}}";
    private volatile String etag;

    /** Fail this many times with {@link #failureStatus} before serving the scripted response. */
    private volatile int failuresBeforeSuccess;
    private volatile int failureStatus = 500;

    RecordingHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    RecordingHttpServer respond(int status, String body) {
        this.status = status;
        this.responseBody = body;
        return this;
    }

    RecordingHttpServer withEtag(String value) {
        this.etag = value;
        return this;
    }

    RecordingHttpServer failFirst(int count, int withStatus) {
        this.failuresBeforeSuccess = count;
        this.failureStatus = withStatus;
        return this;
    }

    List<Request> requests() {
        synchronized (requests) {
            return new ArrayList<Request>(requests);
        }
    }

    Request lastRequest() {
        List<Request> snapshot = requests();
        if (snapshot.isEmpty()) {
            throw new AssertionError("no request was made");
        }
        return snapshot.get(snapshot.size() - 1);
    }

    int requestCount() {
        return requests.size();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getRawQuery() == null
                    ? exchange.getRequestURI().getRawPath()
                    : exchange.getRequestURI().getRawPath() + "?" + exchange.getRequestURI().getRawQuery();
            requests.add(new Request(
                    exchange.getRequestMethod(), path, readBody(exchange), exchange.getRequestHeaders()));

            int attempt = served.incrementAndGet();
            if (attempt <= failuresBeforeSuccess) {
                send(exchange, failureStatus,
                        "{\"success\":false,\"error\":{\"code\":\"SCRIPTED\",\"message\":\"failure "
                                + attempt + "\"}}");
                return;
            }
            if (etag != null) {
                exchange.getResponseHeaders().set("ETag", etag);
            }
            send(exchange, status, responseBody);
        } finally {
            exchange.close();
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (status == 304) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream in = exchange.getRequestBody();
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }
}
