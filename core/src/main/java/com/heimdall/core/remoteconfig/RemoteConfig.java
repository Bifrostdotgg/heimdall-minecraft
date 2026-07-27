package com.heimdall.core.remoteconfig;

import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.tunnel.ConfigPushHandler;
import com.heimdall.core.tunnel.ProtocolMode;
import com.heimdall.core.tunnel.ProtocolModeListener;
import com.heimdall.core.util.Registration;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The configuration the dashboard owns, as this server currently sees it.
 *
 * <h2>Three sources, one answer</h2>
 *
 * <p>In precedence order: a live {@code config.push}, then the disk cache, then the built-in
 * defaults. They <em>overlay</em> rather than replace — a module the bot never mentioned keeps its
 * default rather than silently switching off, which matters because the bot narrows its push to the
 * capabilities the client declared, so "not mentioned" is the ordinary case for anything this jar
 * can do but is not currently doing.
 *
 * <h2>Version monotonicity, and why it is scoped to a connection</h2>
 *
 * <p>Pushes are fire-and-forget frames, so a replayed or reordered one is possible and applying it
 * would silently revert a setting an operator just changed. Within a connection, therefore, only a
 * <strong>strictly newer</strong> version is applied; anything at or below the current one is
 * logged and dropped, including an equal version carrying different content. That is deliberate:
 * within a session the bot's counter is the authority on whether anything changed, and a re-push at
 * an unchanged version is what a reconnect produces rather than what an edit produces. A push is
 * still acknowledged either way — that happens in the tunnel — because an unacknowledged push is
 * one the bot re-sends forever.
 *
 * <p>The floor resets when a connection negotiates v3. That scoping is not incidental: the disk
 * cache outlives the bot's own counter, and a guild document recreated bot-side starts counting
 * again from 1. A plugin holding a cached version 7 would then reject every push it ever received
 * again and run on stale config permanently, fixable only by deleting a file on the server. The bot
 * is the source of truth, so the first push of a connection is authoritative and the ordering
 * guarantee applies where the ordering hazard actually is.
 *
 * <h2>Safe defaults are not optional</h2>
 *
 * <p>Every safety-relevant setting a module reads must have a default that is safe on its own,
 * because there is a real state — first boot, offline, against a v2 bot — in which nothing has ever
 * configured it. "Fail open or fail closed when the bot is unreachable" is exactly such a setting,
 * and it arrives with the modules in phase 1d.
 *
 * <h2>Threading</h2>
 *
 * <p>Reads are lock-free against an immutable snapshot, so a login path never blocks on a config
 * change. Writes are serialised on one lock, and listeners fire on the socket's reading thread from
 * inside the push handler — so listeners must be quick.
 */
public final class RemoteConfig implements ConfigPushHandler, ProtocolModeListener {

    private final HeimdallLogger logger;
    private final ConfigCache cache;
    private final ConfigDocument defaults;

    private final CopyOnWriteArrayList<ConfigListener> documentListeners =
            new CopyOnWriteArrayList<ConfigListener>();
    private final Map<String, CopyOnWriteArrayList<ModuleConfigListener>> moduleListeners =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<ModuleConfigListener>>();

    /** Guards every mutation, so two pushes cannot interleave into a document neither sent. */
    private final Object writeLock = new Object();

    /** The document actually in force — defaults, overlaid by whatever is current. */
    private volatile ConfigDocument effective;

    /** The pushed-or-cached layer, before the defaults are applied underneath it. */
    private volatile ConfigDocument applied = ConfigDocument.empty();

    /** Whether this connection has applied a push yet. See the class javadoc on monotonicity. */
    private volatile boolean acceptedPushThisSession;

    /**
     * @param cachePath where the last pushed document is kept; {@code null} disables caching, which
     *     is only sensible in a test
     * @param defaults the built-in configuration, used for anything nothing upstream mentions
     */
    public RemoteConfig(HeimdallLogger logger, Path cachePath, ConfigDocument defaults) {
        if (logger == null) {
            throw new IllegalArgumentException("logger is required");
        }
        this.logger = logger;
        this.cache = new ConfigCache(logger, cachePath);
        this.defaults = defaults == null ? ConfigDocument.empty() : defaults;
        this.effective = this.defaults;
    }

    /**
     * Restores the cached document, if there is one.
     *
     * <p>Called once at boot, before the tunnel connects, so the plugin is configured from its first
     * moment rather than from whenever the bot answers.
     */
    public void loadFromCache() {
        ConfigDocument cached = cache.load();
        if (cached == ConfigDocument.empty()) {
            return;
        }
        synchronized (writeLock) {
            swap(cached);
        }
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    /** The document in force: defaults overlaid by the live or cached configuration. */
    public ConfigDocument document() {
        return effective;
    }

    /** The version in force, or {@link ConfigDocument#UNVERSIONED}. */
    public int version() {
        return effective.version();
    }

    /** Whether a module should be running. */
    public boolean moduleEnabled(String moduleId) {
        return effective.module(moduleId).enabled();
    }

    /**
     * A module's settings, as a typed view with defaults.
     *
     * <p>Never {@code null}, so {@code moduleSettings("whitelist").intValue("window-minutes", 60)}
     * reads the same before and after the bot has ever configured it.
     */
    public Payload moduleSettings(String moduleId) {
        return effective.module(moduleId).settings();
    }

    /** A module's whole entry. */
    public ModuleConfig moduleConfig(String moduleId) {
        return effective.module(moduleId);
    }

    /** The set of modules that should currently be running. */
    public Set<String> enabledModuleIds() {
        return effective.enabledModuleIds();
    }

    /** Dashboard-owned message templates. */
    public Payload messages() {
        return effective.messages();
    }

    // ── Subscriptions ────────────────────────────────────────────────────────

    /** Subscribes to one module's section. Fired only when that section really changes. */
    public Registration subscribeModule(final String moduleId, final ModuleConfigListener listener) {
        if (moduleId == null || moduleId.isEmpty()) {
            throw new IllegalArgumentException("moduleId is required");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener is required");
        }
        listenersFor(moduleId).add(listener);
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                CopyOnWriteArrayList<ModuleConfigListener> listeners = moduleListeners.get(moduleId);
                if (listeners != null) {
                    listeners.remove(listener);
                }
            }
        });
    }

    /** Subscribes to the whole document. For {@code ModuleManager}; prefer the per-module form. */
    public Registration subscribeAll(final ConfigListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener is required");
        }
        documentListeners.add(listener);
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                documentListeners.remove(listener);
            }
        });
    }

    // ── Tunnel hooks ─────────────────────────────────────────────────────────

    @Override
    public void onConfigPush(Payload payload) {
        ConfigDocument pushed = ConfigDocument.fromPayload(payload);
        synchronized (writeLock) {
            if (acceptedPushThisSession && pushed.version() <= applied.version()) {
                // Acknowledged by the tunnel regardless; see the class javadoc. An EQUAL version is
                // dropped too, deliberately: within one session the bot's counter is the authority
                // on whether anything changed, and a re-push at the same version is the reconnect
                // case rather than an edit.
                logger.warn("ignoring a stale remote config push (version " + pushed.version()
                        + ", already at " + applied.version() + ")");
                return;
            }
            if (!acceptedPushThisSession && pushed.version() < applied.version()) {
                // The connection-scoped floor is doing its job here, and this is the one situation
                // in which it is doing something surprising: the bot's counter has gone BACKWARDS
                // across a reconnect. A guild document recreated bot-side does exactly that. Worth
                // a warning rather than silence, because if it recurs the cause is upstream and
                // nothing in the plugin's own logs would otherwise hint at it.
                logger.warn("the bot's config version went backwards across a reconnect (now "
                        + pushed.version() + ", previously " + applied.version()
                        + ") — applying it, since the bot is the source of truth. If this repeats, "
                        + "the guild's config may have been recreated bot-side.");
            }
            acceptedPushThisSession = true;
            swap(pushed);
            cache.save(pushed);
        }
    }

    /**
     * Resets the per-connection version floor when a connection negotiates v3.
     *
     * <p>Registered on the tunnel as a {@link ProtocolModeListener} so the two stay wired without
     * either package importing the other's concrete type.
     */
    @Override
    public void onModeChanged(ProtocolMode previous, ProtocolMode current) {
        if (current == ProtocolMode.V3) {
            acceptedPushThisSession = false;
            logger.debug("remote config will accept the first push of this connection as authoritative");
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Installs a new layer and tells everyone what actually changed.
     *
     * <p>The snapshot is swapped before any listener runs, so a listener that reads the config back
     * — which is the normal thing for one to do — sees the new value rather than the one it is being
     * told about.
     */
    private void swap(ConfigDocument layer) {
        ConfigDocument previous = effective;
        ConfigDocument next = layer.overlaying(defaults);
        if (next.equals(previous)) {
            logger.debug("remote config version " + layer.version() + " changed nothing");
            applied = layer;
            return;
        }
        applied = layer;
        effective = next;
        logger.info("remote config now at version " + next.version()
                + " (enabled: " + next.enabledModuleIds() + ")");
        notifyModuleListeners(previous, next);
        notifyDocumentListeners(previous, next);
    }

    private void notifyModuleListeners(ConfigDocument previous, ConfigDocument current) {
        // The union of both documents' ids, so a module that disappeared entirely is still told.
        java.util.Set<String> ids = new java.util.LinkedHashSet<String>(previous.moduleIds());
        ids.addAll(current.moduleIds());
        for (String id : ids) {
            ModuleConfig before = previous.module(id);
            ModuleConfig after = current.module(id);
            if (before.equals(after)) {
                continue;
            }
            CopyOnWriteArrayList<ModuleConfigListener> listeners = moduleListeners.get(id);
            if (listeners == null) {
                continue;
            }
            for (ModuleConfigListener listener : listeners) {
                try {
                    listener.onModuleConfigChanged(id, before, after);
                } catch (RuntimeException e) {
                    logger.error("config listener for module '" + id + "' threw", e);
                }
            }
        }
    }

    private void notifyDocumentListeners(ConfigDocument previous, ConfigDocument current) {
        for (ConfigListener listener : documentListeners) {
            try {
                listener.onConfigChanged(previous, current);
            } catch (RuntimeException e) {
                logger.error("remote config listener threw", e);
            }
        }
    }

    private CopyOnWriteArrayList<ModuleConfigListener> listenersFor(String moduleId) {
        CopyOnWriteArrayList<ModuleConfigListener> existing = moduleListeners.get(moduleId);
        if (existing != null) {
            return existing;
        }
        CopyOnWriteArrayList<ModuleConfigListener> created =
                new CopyOnWriteArrayList<ModuleConfigListener>();
        CopyOnWriteArrayList<ModuleConfigListener> raced =
                moduleListeners.putIfAbsent(moduleId, created);
        return raced == null ? created : raced;
    }
}
