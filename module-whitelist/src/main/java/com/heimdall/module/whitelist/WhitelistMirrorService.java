package com.heimdall.module.whitelist;

import com.heimdall.core.http.ApiClient;
import com.heimdall.core.http.model.WhitelistSyncEntry;
import com.heimdall.core.http.model.WhitelistSyncResult;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.mirror.MirrorStore;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.session.PlayerSessionListener;
import com.heimdall.core.util.Strings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The local copy of the whitelist, kept warm so a bot outage is invisible.
 *
 * <h2>What the pre-warm poll is actually for</h2>
 *
 * <p>A cache that only holds players who happened to connect recently protects only those players.
 * Pulling the <em>whole</em> whitelist on a cadence is what makes a bot redeploy, a crash or a
 * network blip invisible to everybody on it — which, combined with
 * {@link WhitelistSettings.FallbackMode#WHITELIST_ONLY}, is the difference between a five-minute
 * deploy nobody notices and one that locks a server's entire player base out.
 *
 * <p>A failed poll leaves the mirror <strong>exactly as it was</strong>. It is never cleared,
 * because the moment the bot is unreachable is precisely the moment its contents are load-bearing.
 * That is v2's rule and it is the whole resilience argument in one line.
 *
 * <h2>The ETag</h2>
 *
 * <p>The mirror persists the last ETag (departure D11), so a restart does not pull a full dump it
 * already has, and a poll against an unchanged whitelist is a {@code 304} with no body. The store
 * discards the ETag by itself if loading dropped an unusable row (D12) — a mirror that is missing
 * entries must not keep answering {@code 304} against them.
 *
 * <h2>Join and quit windows</h2>
 *
 * <p>A player who successfully joined, and one who played and left, have both demonstrated they were
 * allowed on, so their entry slides forward. The windows are read live on every event, so a
 * dashboard edit takes effect immediately — unlike the base window and the ceiling, which are baked
 * into the {@code MirrorPolicy} at open time.
 *
 * <p><strong>The slide sets rather than extends</strong>, so a shorter window can pull an expiry
 * back in — a 120-minute join extension landing after a 180-minute leave extension moves it
 * earlier. That is v2 verbatim and deliberately kept; see non-departure N1.
 *
 * <h2>Threading and ownership</h2>
 *
 * <p>The store is opened through {@code ModuleContext}, so it is closed — and flushed — when the
 * module is disabled, without this class remembering to. {@link #syncNow()} blocks and runs on
 * {@code heimdall-sched}; the window callbacks run on {@code heimdall-io}; reads happen on whatever
 * thread a login arrives on. {@code MirrorStore} is safe for all of that.
 */
final class WhitelistMirrorService {

    /** The mirror's file name, under {@code plugins/Heimdall/modules/whitelist/}. */
    static final String MIRROR_NAME = "whitelist-mirror";

    private final HeimdallLogger logger;
    private final ApiClient api;
    private final MirrorStore<String> mirror;
    private final Supplier<WhitelistSettings> settings;

    WhitelistMirrorService(
            HeimdallLogger logger,
            ApiClient api,
            MirrorStore<String> mirror,
            Supplier<WhitelistSettings> settings) {
        this.logger = logger;
        this.api = api;
        this.mirror = mirror;
        this.settings = settings;
    }

    // ── Reads and writes on the login path ───────────────────────────────────

    /** Whether the mirror currently vouches for this player. */
    boolean isWhitelisted(UUID uuid) {
        return mirror.isPresent(key(uuid));
    }

    /**
     * Records a player the bot has just confirmed.
     *
     * <p>Advances {@code lastVerified}, which is what re-bases the #771 ceiling — so it must only be
     * called for a player the bot actually vouched for on this request, never for one served from
     * the mirror.
     */
    void record(UUID uuid, String username) {
        mirror.record(key(uuid), Strings.trimToEmpty(username));
    }

    /**
     * Refreshes a cached player's username without touching {@code lastVerified}.
     *
     * <p>Departure D15. Using {@code record} here instead would renew a revoked player's ceiling
     * every time they changed their name, which is the one thing #771 exists to prevent.
     */
    void refreshUsername(UUID uuid, String username) {
        if (Strings.isNotBlank(username)) {
            mirror.touchValue(key(uuid), username.trim());
        }
    }

    /** For {@code /hd whitelist} in phase 1e, and for the log line after a sync. */
    String stats() {
        return mirror.stats();
    }

    // ── The pre-warm poll ────────────────────────────────────────────────────

    /**
     * Pulls the full whitelist and reconciles it into the mirror. Blocking; never throws.
     *
     * <p>Bounded by the sync endpoint's own budget rather than the join budget: {@code
     * whitelist/sync} runs with a longer per-attempt timeout, and bounding it on the wrong one is
     * what reopens issue #797 / MC-6 (departure D16).
     */
    void syncNow() {
        if (!settings.get().prewarmEnabled()) {
            return;
        }
        if (api == null || !api.settings().isUsable()) {
            // The discovering state, or a server that was never set up. Not a warning: it is the
            // state every server is in for the first seconds of its life.
            logger.debug("skipping the whitelist pre-warm: this server has no guild yet");
            return;
        }
        try {
            WhitelistSyncResult result = api.whitelistSync(mirror.lastEtag())
                    .get(api.settings().whitelistSyncJoinTimeoutMs(), TimeUnit.MILLISECONDS);
            if (result.notModified()) {
                logger.debug(() -> "whitelist unchanged since the last sync (" + mirror.stats() + ")");
                return;
            }
            Map<String, String> authoritative = new LinkedHashMap<String, String>();
            for (WhitelistSyncEntry entry : result.players()) {
                if (Strings.isNotBlank(entry.uuid())) {
                    authoritative.put(entry.uuid().trim().toLowerCase(java.util.Locale.ROOT),
                            Strings.trimToEmpty(entry.username()));
                }
            }
            mirror.reconcile(authoritative);
            // Stored only after a successful reconcile. Recording it first would mean a reconcile
            // that failed half-way left the mirror answering 304 against rows it never applied.
            mirror.setLastEtag(result.etag());
        } catch (Exception failed) {
            // Everything, including an interrupt and a timeout. The mirror is left exactly as it
            // was: a transient bot outage must not erase the very entries that protect against it.
            Thread.interrupted();
            logger.warn("whitelist pre-warm failed; the mirror is unchanged (" + mirror.stats()
                    + "): " + rootMessage(failed));
        }
    }

    /** Drops entries whose window has closed. Cheap, and keeps the file from growing forever. */
    void sweepExpired() {
        mirror.sweepExpired();
    }

    // ── Session windows ──────────────────────────────────────────────────────

    /** The join listener: slides an entry forward by {@code extendOnJoin}. */
    PlayerSessionListener onJoin() {
        return new PlayerSessionListener() {
            @Override
            public void onPlayerSession(PlayerHandle player, long timestampMs) {
                slide(player, settings.get().extendOnJoinMs(), "join");
            }
        };
    }

    /** The quit listener: slides an entry forward by {@code extendOnLeave}. */
    PlayerSessionListener onQuit() {
        return new PlayerSessionListener() {
            @Override
            public void onPlayerSession(PlayerHandle player, long timestampMs) {
                slide(player, settings.get().extendOnLeaveMs(), "quit");
            }
        };
    }

    private void slide(PlayerHandle player, long windowMs, String because) {
        // A name change while they were away is worth picking up, and touchValue deliberately does
        // not move lastVerified — see D15.
        refreshUsername(player.uuid(), player.name());
        boolean extended = mirror.extendOnEvent(key(player.uuid()), windowMs);
        if (!extended) {
            // Ordinary: a bypassed player, or one admitted while the whitelist was switched off, was
            // never mirrored in the first place. Debug rather than warn.
            logger.debug(() -> "no mirror entry to extend for " + player.name() + " on " + because);
        }
    }

    // ── Wiring ───────────────────────────────────────────────────────────────

    /**
     * Opens the mirror under the module's own directory.
     *
     * <p>Static and separate from the constructor because the policy has to be built from the
     * settings first, and {@code ModuleContext.mirror} is what ties the store's lifetime to the
     * module's.
     */
    static MirrorStore<String> openMirror(ModuleContext context, WhitelistSettings settings) {
        return context.mirror(MIRROR_NAME, String.class, com.heimdall.core.mirror.MirrorPolicy.builder()
                .windowMs(settings.cacheWindowMs())
                .maxExtensionHours(settings.maxExtensionHours())
                .build());
    }

    /**
     * The mirror's key for a player.
     *
     * <p>Lower-cased, because the bot's {@code whitelist/sync} and its {@code connection-attempt}
     * response are not guaranteed to agree on the case of a hyphenated UUID, and a mirror keyed on
     * two spellings of the same player holds them as two entries — one of which is never hit and
     * never pruned.
     */
    private static String key(UUID uuid) {
        return uuid == null ? "" : uuid.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        String message = failure.getMessage();
        while (current.getCause() != null) {
            current = current.getCause();
            if (current.getMessage() != null) {
                message = current.getMessage();
            }
        }
        return message == null ? failure.getClass().getSimpleName() : message;
    }

    /** Only for the tests that need to reach past the service at the store itself. */
    MirrorStore<String> store() {
        return mirror;
    }

    /** The keys currently held. For {@code /hd whitelist} in 1e, and for assertions. */
    List<String> keys() {
        return new java.util.ArrayList<String>(mirror.keys());
    }
}
