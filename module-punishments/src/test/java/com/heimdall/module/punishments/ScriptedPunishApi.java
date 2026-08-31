package com.heimdall.module.punishments;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import java.util.concurrent.CopyOnWriteArrayList;

/** Loopback bot stand-in for flushQueue tests. Ignores HMAC; routes on path suffix. */
final class ScriptedPunishApi implements AutoCloseable {

    static final class Hit {
        final String method;
        final String path;
        final String body;

        Hit(String method, String path, String body) {
            this.method = method;
            this.path = path;
            this.body = body == null ? "" : body;
        }
    }

    private final HttpServer server;
    private final List<Hit> hits = new CopyOnWriteArrayList<Hit>();
    private volatile int issueStatus = 200;
    private volatile String issueBody =
            "{\"success\":true,\"data\":{\"id\":\"mongo-1\",\"type\":\"ban\"}}";
    private volatile int revokeStatus = 200;
    private volatile String revokeBody = "{\"success\":true,\"data\":{\"revoked\":1}}";
    private volatile String syncBody =
            "{\"success\":true,\"data\":{\"punishments\":[],\"hash\":\"\\\"etag-1\\\"\"}}";
    private volatile String syncEtag = "\"etag-1\"";

    ScriptedPunishApi() {
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

    ScriptedPunishApi issueResponds(int status, String body) {
        this.issueStatus = status;
        this.issueBody = body;
        return this;
    }

    ScriptedPunishApi revokeResponds(int status, String body) {
        this.revokeStatus = status;
        this.revokeBody = body;
        return this;
    }

    ScriptedPunishApi syncResponds(String body, String etag) {
        this.syncBody = body;
        this.syncEtag = etag;
        return this;
    }

    List<Hit> hits() {
        return Collections.unmodifiableList(new ArrayList<Hit>(hits));
    }

    int countSuffix(String suffix) {
        int n = 0;
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).path.endsWith(suffix)) n++;
        }
        return n;
    }

    int countRevokes() {
        int n = 0;
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).path.contains("/revoke")) n++;
        }
        return n;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String body = read(exchange.getRequestBody());
        hits.add(new Hit(exchange.getRequestMethod(), path, body));
        if ("GET".equals(exchange.getRequestMethod()) && path.endsWith("/punishments/sync")) {
            if (syncEtag != null) {
                exchange.getResponseHeaders().set("ETag", syncEtag);
            }
            send(exchange, 200, syncBody);
            return;
        }
        if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/punishments/revoke")) {
            send(exchange, revokeStatus, revokeBody);
            return;
        }
        if ("POST".equals(exchange.getRequestMethod()) && path.contains("/punishments/")
                && path.endsWith("/revoke")) {
            send(exchange, revokeStatus, revokeBody);
            return;
        }
        if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/punishments")) {
            send(exchange, issueStatus, issueBody);
            return;
        }
        send(exchange, 404, "{\"success\":false,\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"no\"}}");
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
