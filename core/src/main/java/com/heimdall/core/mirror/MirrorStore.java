package com.heimdall.core.mirror;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.AtomicFiles;
import com.heimdall.core.util.Strings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.LongSupplier;

/**
 * A disk-backed local mirror of state the bot owns, with a hard bound on how stale it may get.
 *
 * <p>Generalised from v2's {@code WhitelistCache}: the whitelist is only the first thing worth
 * mirroring, and the interesting semantics are not whitelist-specific.
 *
 * <h2>What it is for</h2>
 *
 * <p>Restart resilience. If the bot is redeploying when a player joins, the mirror answers, and the
 * outage is invisible to them. That only works if the mirror is trustworthy, which is what the rest
 * of this contract is about.
 *
 * <h2>The max-extension ceiling</h2>
 *
 * <p>Two timestamps per entry, and the difference between them is the whole design (v2, issue
 * #771). {@code cacheExpiry} slides forward on ordinary activity via {@link #extendOnEvent};
 * {@code lastVerified} moves <strong>only</strong> when the bot actually confirmed the value, in
 * {@link #record} or {@link #reconcile}. A read serves the entry only while
 *
 * <pre>now &lt;= min(cacheExpiry, lastVerified + maxExtensionMs)</pre>
 *
 * <p>so a player removed from the Discord whitelist cannot keep access indefinitely by rejoining
 * once per extension window. The ceiling is applied on write <em>and</em> re-checked on read: even
 * an entry written by an older version, or one hand-edited on disk, cannot be served past it.
 * Entries with no {@code lastVerified} deserialize to 0, whose ceiling is far in the past, so a
 * legacy file forces re-verification rather than being trusted. Setting {@link
 * MirrorPolicy#maxExtensionMs()} to 0 disables the bound.
 *
 * <h2>Reconcile and the never-throw contract</h2>
 *
 * <p>{@link #reconcile} makes the mirror match an authoritative set exactly, which includes
 * <em>pruning</em> anything absent from it — that is how a revocation propagates promptly. So the
 * caller must only ever call it with the result of a <strong>successful</strong> full fetch. A
 * failed fetch must leave the mirror untouched; there is deliberately no "reconcile with what I
 * managed to get" entry point, because an empty list is a legitimate "nobody is whitelisted" state
 * and cannot be distinguished from a partial failure after the fact.
 *
 * <h2>Persistence</h2>
 *
 * <p>Saves are debounced onto the supplied scheduler and written atomically — see {@link
 * DebouncedWriter} and {@link AtomicFiles} for the two v2 defects that motivates. {@link #close()}
 * flushes, so nothing is lost at shutdown.
 *
 * <p>Thread-safe.
 *
 * @param <T> the mirrored value type; must be serialisable by Gson
 */
public final class MirrorStore<T> implements AutoCloseable {

    private final HeimdallLogger logger;
    private final Path file;
    private final MirrorPolicy policy;
    private final LongSupplier clock;
    private final Gson gson = new Gson();
    private final Type snapshotType;
    private final ConcurrentHashMap<String, MirrorEntry<T>> entries =
            new ConcurrentHashMap<String, MirrorEntry<T>>();
    private final DebouncedWriter writer;

    private volatile String lastEtag;

    /**
     * Opens a mirror, loading whatever is already on disk.
     *
     * @param file where the mirror is persisted; parent directories are created on first save
     * @param valueType the mirrored value's type, needed because Gson cannot see {@code T}
     * @param scheduler where debounced saves run — typically {@code HeimdallExecutors.scheduler()}
     */
    public static <T> MirrorStore<T> open(
            HeimdallLogger logger,
            Path file,
            Class<T> valueType,
            MirrorPolicy policy,
            ScheduledExecutorService scheduler) {
        return open(logger, file, (Type) valueType, policy, scheduler, new LongSupplier() {
            @Override
            public long getAsLong() {
                return System.currentTimeMillis();
            }
        });
    }

    /** As {@link #open}, with the clock injected so the ceiling can be tested without sleeping. */
    static <T> MirrorStore<T> open(
            HeimdallLogger logger,
            Path file,
            Type valueType,
            MirrorPolicy policy,
            ScheduledExecutorService scheduler,
            LongSupplier clock) {
        return new MirrorStore<T>(logger, file, valueType, policy, scheduler, clock);
    }

    private MirrorStore(
            HeimdallLogger logger,
            Path file,
            Type valueType,
            MirrorPolicy policy,
            ScheduledExecutorService scheduler,
            LongSupplier clock) {
        if (logger == null || file == null || valueType == null || policy == null || clock == null) {
            throw new IllegalArgumentException("logger, file, valueType, policy and clock are required");
        }
        this.logger = logger;
        this.file = file;
        this.policy = policy;
        this.clock = clock;
        this.snapshotType = TypeToken.getParameterized(MirrorSnapshot.class, valueType).getType();
        this.writer = new DebouncedWriter(
                logger, scheduler, policy.saveDebounceMs(), file.toString(), new Runnable() {
                    @Override
                    public void run() {
                        writeSnapshot();
                    }
                });
        load();
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    /**
     * The mirrored value, if it is still trustworthy.
     *
     * <p>An entry past its effective expiry is evicted here rather than merely ignored, so a stale
     * value cannot be resurrected by a later {@link #extendOnEvent}.
     *
     * @return the value, or {@code null} if absent or expired
     */
    public T get(String key) {
        if (key == null) {
            return null;
        }
        MirrorEntry<T> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (clock.getAsLong() > effectiveExpiry(entry)) {
            entries.remove(key, entry);
            writer.markDirty();
            return null;
        }
        return entry.value();
    }

    /** Whether {@link #get} would return a value. Evicts an expired entry, exactly as {@code get} does. */
    public boolean isPresent(String key) {
        return get(key) != null;
    }

    /** How many entries are held, expired ones included until a read or sweep removes them. */
    public int size() {
        return entries.size();
    }

    /** A snapshot of the keys currently held. */
    public Set<String> keys() {
        return Collections.unmodifiableSet(new HashSet<String>(entries.keySet()));
    }

    /**
     * The ETag from the last successful fetch that populated this mirror, or {@code null}.
     *
     * <p>Persisted with the entries so a restart can resume conditional polling instead of pulling
     * a full dump it already has.
     */
    public String lastEtag() {
        return lastEtag;
    }

    /** Records the ETag that accompanied the data currently mirrored. */
    public void setLastEtag(String etag) {
        this.lastEtag = etag;
        writer.markDirty();
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /**
     * Records a value the bot has just confirmed.
     *
     * <p>This is a real verification: {@code lastVerified} moves to now, so the ceiling is measured
     * from now and the entry gets a full fresh window.
     */
    public void record(String key, T value) {
        if (Strings.isBlank(key) || value == null) {
            logger.warn("Refusing to mirror an entry with a blank key or null value");
            return;
        }
        long now = clock.getAsLong();
        entries.put(key, new MirrorEntry<T>(value, now, now + policy.windowMs(), now));
        writer.markDirty();
    }

    /**
     * Slides an existing entry's expiry forward on ordinary activity — a join, a leave.
     *
     * <p><strong>Does not touch {@code lastVerified}</strong>, and is capped by the ceiling measured
     * from it. That is what stops activity alone from keeping a revoked value alive.
     *
     * <p>Does nothing for a key the mirror does not hold: activity is not evidence, so this can
     * never create an entry.
     *
     * @param windowMs how far forward to slide, before capping
     * @return whether an entry was found and extended
     */
    public boolean extendOnEvent(String key, long windowMs) {
        if (key == null) {
            return false;
        }
        MirrorEntry<T> entry = entries.get(key);
        if (entry == null) {
            return false;
        }
        long now = clock.getAsLong();
        entry.lastConnection(now);
        entry.cacheExpiry(cap(entry, now + Math.max(0, windowMs)));
        writer.markDirty();
        return true;
    }

    /**
     * Makes the mirror match {@code authoritative} exactly.
     *
     * <p>Every supplied entry is treated as a fresh verification. Existing entries keep the later of
     * their current expiry and a capped {@code now + window}, so a recent extension is never shrunk
     * by a sync. Everything <em>not</em> supplied is pruned.
     *
     * <p>Only ever call this with a successful full fetch — see the class javadoc.
     *
     * @param authoritative the complete current set; an empty map is a legitimate "nothing is
     *     mirrored any more" and is applied as such
     */
    public ReconcileResult reconcile(Map<String, T> authoritative) {
        if (authoritative == null) {
            throw new IllegalArgumentException("the authoritative set is required — pass an empty map, not null");
        }
        long now = clock.getAsLong();
        Set<String> seen = new HashSet<String>();
        int added = 0;
        int updated = 0;

        for (Map.Entry<String, T> incoming : authoritative.entrySet()) {
            String key = incoming.getKey();
            T value = incoming.getValue();
            if (Strings.isBlank(key) || value == null) {
                continue;
            }
            seen.add(key);

            MirrorEntry<T> existing = entries.get(key);
            if (existing == null) {
                entries.put(key, new MirrorEntry<T>(value, now, now + policy.windowMs(), now));
                added++;
            } else {
                existing.value(value);
                existing.lastVerified(now);
                // Refresh from this verification, but never shrink an entry a recent event already
                // slid further forward. The cap uses the just-updated lastVerified, so the ceiling
                // has moved forward too and this cannot exceed it.
                existing.cacheExpiry(Math.max(existing.cacheExpiry(), cap(existing, now + policy.windowMs())));
                updated++;
            }
        }

        int pruned = 0;
        for (String key : new HashSet<String>(entries.keySet())) {
            if (!seen.contains(key)) {
                entries.remove(key);
                pruned++;
            }
        }

        ReconcileResult result = new ReconcileResult(added, updated, pruned);
        writer.markDirty();
        logger.info("Mirror reconcile (" + file.getFileName() + "): " + result + " (" + entries.size() + " held)");
        return result;
    }

    /**
     * Drops every entry past its effective expiry.
     *
     * <p>Reads evict lazily; this is the periodic sweep for entries nobody is asking about, which
     * would otherwise sit in memory and in the file forever.
     *
     * @return how many were removed
     */
    public int sweepExpired() {
        long now = clock.getAsLong();
        int removed = 0;
        for (Map.Entry<String, MirrorEntry<T>> entry : entries.entrySet()) {
            if (now > effectiveExpiry(entry.getValue())) {
                entries.remove(entry.getKey(), entry.getValue());
                removed++;
            }
        }
        if (removed > 0) {
            writer.markDirty();
            logger.debug("Swept " + removed + " expired mirror entries from " + file.getFileName());
        }
        return removed;
    }

    /** Empties the mirror, ETag included — the next fetch must be a full one. */
    public void clear() {
        entries.clear();
        lastEtag = null;
        writer.markDirty();
    }

    /** Writes any pending changes now. */
    public void flush() {
        writer.flush();
    }

    /** Flushes and stops debouncing. Safe to call more than once. */
    @Override
    public void close() {
        writer.close();
    }

    /** How many times the mirror has actually been written to disk. Diagnostics and tests. */
    long writeCount() {
        return writer.writeCount();
    }

    /** A one-line summary for a status command. */
    public String stats() {
        long now = clock.getAsLong();
        int expired = 0;
        for (MirrorEntry<T> entry : entries.values()) {
            if (now > effectiveExpiry(entry)) {
                expired++;
            }
        }
        return entries.size() + " entries (" + expired + " expired), etag="
                + (lastEtag == null ? "none" : lastEtag);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * When an entry really stops being trustworthy: its own expiry, clamped by the ceiling.
     *
     * <p>Enforced on read as well as on write on purpose. The write-side cap keeps the persisted
     * value honest; this keeps a value that got past it — an older version's file, a hand edit —
     * from ever being served.
     */
    private long effectiveExpiry(MirrorEntry<T> entry) {
        if (!policy.isExtensionBounded()) {
            return entry.cacheExpiry();
        }
        return Math.min(entry.cacheExpiry(), entry.lastVerified() + policy.maxExtensionMs());
    }

    /** Clamps a proposed expiry to the ceiling, so nothing on disk ever claims more than it may have. */
    private long cap(MirrorEntry<T> entry, long proposedExpiry) {
        if (!policy.isExtensionBounded()) {
            return proposedExpiry;
        }
        return Math.min(proposedExpiry, entry.lastVerified() + policy.maxExtensionMs());
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            MirrorSnapshot<T> snapshot = gson.fromJson(json, snapshotType);
            if (snapshot == null || snapshot.entries == null) {
                return;
            }
            lastEtag = snapshot.etag;
            for (Map.Entry<String, MirrorEntry<T>> entry : snapshot.entries.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue().value() != null) {
                    entries.put(entry.getKey(), entry.getValue());
                }
            }
            logger.info("Loaded " + entries.size() + " mirror entries from " + file.getFileName());
        } catch (IOException e) {
            logger.error("Could not read " + file + " — starting with an empty mirror", e);
        } catch (RuntimeException e) {
            // A truncated or hand-mangled file must not stop the plugin booting. An empty mirror
            // means the next fetch repopulates it; a failed boot means nobody can join at all.
            logger.error("Could not parse " + file + " — starting with an empty mirror", e);
        }
    }

    private void writeSnapshot() {
        MirrorSnapshot<T> snapshot = new MirrorSnapshot<T>();
        snapshot.etag = lastEtag;
        snapshot.entries = new LinkedHashMap<String, MirrorEntry<T>>(entries);
        try {
            AtomicFiles.writeUtf8(file, gson.toJson(snapshot, snapshotType));
        } catch (IOException e) {
            // Surfaced to DebouncedWriter, which logs it and keeps the dirty flag set so the next
            // flush retries.
            throw new UncheckedIOException(e);
        }
    }

    /** The on-disk document: the entries plus the ETag they came with. */
    static final class MirrorSnapshot<T> {

        String etag;
        Map<String, MirrorEntry<T>> entries;
    }
}
