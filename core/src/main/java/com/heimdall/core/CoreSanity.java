package com.heimdall.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import java.io.IOException;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Build-level proof that every shaded third-party dependency resolves and that its API is usable
 * from Java 8 source.
 *
 * <p>This class is deliberately trivial: it exists so that javac has to actually read Gson,
 * nv-websocket-client and SnakeYAML while the source level is 8 — an API that had moved on to
 * {@code var}, records or default-method shapes we cannot express would fail here.
 *
 * <p>It does <em>not</em> prove those libraries ship Java 8 <em>bytecode</em>. That is a common
 * assumption and it is wrong: javac at {@code --release 8} reads a Java 17 classfile off the
 * compile classpath without complaint (verified against this repo's own Velocity module). The
 * bytecode guarantee comes from {@code :app:verifyShadowJar}, which reads the merged jar and
 * fails the build on any classfile too new for the oldest supported JVM.
 *
 * <p>Not part of the plugin's runtime behaviour; replaced by real code in phase 1.
 */
public final class CoreSanity {

    private static final Gson GSON = new Gson();

    private CoreSanity() {
    }

    /** Round-trips a value through Gson. */
    public static String toJson(String key, String value) {
        JsonObject object = new JsonObject();
        object.addProperty(key, value);
        return GSON.toJson(object);
    }

    /** Parses a YAML document through SnakeYAML. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseYaml(String document) {
        return (Map<String, Object>) new Yaml().load(document);
    }

    /**
     * Forces javac to link against nv-websocket-client's factory and socket types.
     *
     * <p>Nothing is connected — the socket is created and handed straight back. Phase 1 replaces
     * this with a core-owned socket abstraction so the library stays swappable.
     */
    public static WebSocket createSocket(String url) throws IOException, WebSocketException {
        return new WebSocketFactory().createSocket(url);
    }
}
