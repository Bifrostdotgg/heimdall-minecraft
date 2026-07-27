package com.heimdall.core.remoteconfig;

import com.heimdall.core.json.Payload;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A whole configuration document: {@code {version, modules, messages}}.
 *
 * <p>Immutable, and swapped wholesale. That is what makes a config change atomic from every
 * reader's point of view: a login in flight sees either the old document or the new one, never a
 * half-applied mixture of the two. v2 mutated its config in place and a reload could be observed
 * with the new messages and the old timings.
 *
 * <p>The {@code version} is the bot's monotonic counter, and it is how a replayed or reordered push
 * is recognised — frames are fire-and-forget, so ordering is not guaranteed by the transport.
 */
public final class ConfigDocument {

    /** The version of a document that has never been configured by anything. */
    public static final int UNVERSIONED = -1;

    private static final ConfigDocument EMPTY = new ConfigDocument(
            UNVERSIONED,
            Collections.<String, ModuleConfig>emptyMap(),
            Payload.empty());

    private final int version;
    private final Map<String, ModuleConfig> modules;
    private final Payload messages;

    private ConfigDocument(int version, Map<String, ModuleConfig> modules, Payload messages) {
        this.version = version;
        this.modules = modules;
        this.messages = messages;
    }

    /** No version, no modules, no messages. */
    public static ConfigDocument empty() {
        return EMPTY;
    }

    /** Builds a document directly — how the built-in defaults are expressed. */
    public static ConfigDocument of(int version, Map<String, ModuleConfig> modules, Payload messages) {
        Map<String, ModuleConfig> copy = new LinkedHashMap<String, ModuleConfig>();
        if (modules != null) {
            for (Map.Entry<String, ModuleConfig> entry : modules.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return new ConfigDocument(
                version,
                Collections.unmodifiableMap(copy),
                messages == null ? Payload.empty() : messages);
    }

    /**
     * Parses a {@code config.push} body, or a cached copy of one.
     *
     * <p>Never throws. A document that arrives malformed produces empty sections rather than a
     * failure — the alternative is a bad push from the dashboard taking a server's login path down,
     * and the whole point of the disk cache and the defaults is that there is always something to
     * fall back to.
     */
    public static ConfigDocument fromPayload(Payload payload) {
        if (payload == null || payload.isEmpty()) {
            return EMPTY;
        }
        Payload modulesPayload = payload.child("modules");
        Map<String, ModuleConfig> modules = new LinkedHashMap<String, ModuleConfig>();
        for (String id : modulesPayload.keys()) {
            modules.put(id, ModuleConfig.fromPayload(modulesPayload.child(id)));
        }
        return new ConfigDocument(
                payload.intValue("version", UNVERSIONED),
                Collections.unmodifiableMap(modules),
                payload.child("messages"));
    }

    /** This document in the wire shape, which is also the on-disk cache shape. */
    public Payload toPayload() {
        Payload.Builder modulesPayload = Payload.builder();
        for (Map.Entry<String, ModuleConfig> entry : modules.entrySet()) {
            modulesPayload.put(entry.getKey(), entry.getValue().toPayload());
        }
        return Payload.builder()
                .put("version", version)
                .put("modules", modulesPayload.build())
                .put("messages", messages)
                .build();
    }

    /** The bot's monotonic config counter, or {@link #UNVERSIONED}. */
    public int version() {
        return version;
    }

    /** One module's entry, or {@link ModuleConfig#absent()}. Never {@code null}. */
    public ModuleConfig module(String id) {
        ModuleConfig config = modules.get(id);
        return config == null ? ModuleConfig.absent() : config;
    }

    /** Every module id mentioned, in document order. */
    public Set<String> moduleIds() {
        return modules.keySet();
    }

    /** The module ids whose entry says {@code enabled}. */
    public Set<String> enabledModuleIds() {
        Set<String> enabled = new LinkedHashSet<String>();
        for (Map.Entry<String, ModuleConfig> entry : modules.entrySet()) {
            if (entry.getValue().enabled()) {
                enabled.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(enabled);
    }

    /** Dashboard-owned message templates. */
    public Payload messages() {
        return messages;
    }

    /**
     * This document laid over {@code base}: every module {@code base} knows about, with this one's
     * entries winning where they collide.
     *
     * <p>How "live push &gt; disk cache &gt; built-in defaults" is actually expressed. Replacing
     * wholesale instead would mean a module the bot has never heard of — because the client did not
     * declare its capability, which is exactly what the bot narrows its push by — losing its
     * built-in default and silently switching off.
     *
     * <p>Messages merge per key for the same reason: the bot overriding one template must not
     * delete the rest.
     */
    public ConfigDocument overlaying(ConfigDocument base) {
        if (base == null || base == EMPTY) {
            return this;
        }
        Map<String, ModuleConfig> merged = new LinkedHashMap<String, ModuleConfig>(base.modules);
        merged.putAll(modules);
        return new ConfigDocument(
                version,
                Collections.unmodifiableMap(merged),
                Payload.builder().putAll(base.messages).putAll(messages).build());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ConfigDocument)) {
            return false;
        }
        ConfigDocument that = (ConfigDocument) other;
        return version == that.version
                && modules.equals(that.modules)
                && messages.equals(that.messages);
    }

    @Override
    public int hashCode() {
        int result = version;
        result = 31 * result + modules.hashCode();
        result = 31 * result + messages.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "ConfigDocument{version=" + version + ", modules=" + modules.keySet()
                + ", enabled=" + enabledModuleIds() + "}";
    }
}
