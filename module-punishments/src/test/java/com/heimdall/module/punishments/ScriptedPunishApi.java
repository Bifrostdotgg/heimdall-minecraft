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

/**
 * Loopback bot stand-in for flushQueue tests. Ignores HMAC; routes on path suffix.
 *
 * <p>It does honour {@code If-None-Match} on {@code /punishments/sync}, because that is the one
 * piece of real-bot behaviour a test cannot do without: the plugin re-syncs after every
 * successful upload and every five minutes, so a stub that answered 200 with the same canned
 * snapshot each time would reconcile the mirror at an unpredictable moment. Change the snapshot
 * with {@link #syncResponds(String, String)} and give it a new ETag to make a poll take effect.
 */
final class ScriptedPunishApi implements AutoCloseable {

    static final class Hit {
        final String method;
        final String path;
        /** The raw query string, "" when there was none. The {@code includeHidden} flag lives here. */
        final String query;
        final String body;

        Hit(String method, String path, String query, String body) {
            this.method = method;
            this.path = path;
            this.query = query == null ? "" : query;
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
    private volatile String playerBody = "{\"success\":true,\"data\":{\"punishments\":[]}}";
    private volatile String listBody = "{\"success\":true,\"data\":{\"punishments\":[]}}";

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

    /** What {@code GET punishments/player/:uuid} answers - the rows {@code /history} renders. */
    ScriptedPunishApi playerResponds(String body) {
        this.playerBody = body;
        return this;
    }

    /** What {@code GET punishments} answers - the rows {@code /staffhistory} and /banlist read. */
    ScriptedPunishApi listResponds(String body) {
        this.listBody = body;
        return this;
    }

    /** The last hit whose path ends with {@code suffix}, or null. */
    Hit lastSuffix(String suffix) {
        Hit found = null;
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).path.endsWith(suffix)) found = hits.get(i);
        }
        return found;
    }

    /** The last GET whose path contains {@code fragment}, or null. */
    Hit lastGetContaining(String fragment) {
        Hit found = null;
        for (int i = 0; i < hits.size(); i++) {
            if ("GET".equals(hits.get(i).method) && hits.get(i).path.contains(fragment)) {
                found = hits.get(i);
            }
        }
        return found;
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
        hits.add(new Hit(exchange.getRequestMethod(), path,
                exchange.getRequestURI().getRawQuery(), body));
        if ("GET".equals(exchange.getRequestMethod()) && path.endsWith("/punishments/sync")) {
            if (syncEtag != null) {
                exchange.getResponseHeaders().set("ETag", syncEtag);
            }
            // A real bot answers 304 when the caller already has this snapshot, and the plugin
            // polls every five minutes plus once after every successful upload. Answering 200
            // with the same body every time made the stub reconcile the mirror to whatever the
            // canned list says on an unpredictable schedule, which raced every test that puts a
            // row in the mirror and then acts on it: the poll could land between the two and
            // wipe the row. That is a property of this fixture, not of the plugin.
            if (syncEtag != null && syncEtag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            send(exchange, 200, syncBody);
            return;
        }
        if ("GET".equals(exchange.getRequestMethod()) && path.contains("/punishments/player/")) {
            send(exchange, 200, playerBody);
            return;
        }
        if ("GET".equals(exchange.getRequestMethod()) && path.endsWith("/punishments")) {
            send(exchange, 200, listBody);
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
