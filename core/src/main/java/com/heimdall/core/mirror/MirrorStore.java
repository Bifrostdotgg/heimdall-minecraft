package com.heimdall.core.mirror;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.Strings;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * A disk-backed local mirror of state the bot owns, with a hard bound on how stale it may get.
 *
 * <p>Generalised from v2's {@code WhitelistCache}: the whitelist is only the first thing worth
 * mirroring, and the interesting semantics are not whitelist-specific. Persistence lives in {@link
 * MirrorFile}; this class is the rules.
 *
 * <h2>What it is for</h2>
 *
 * <p>Restart resilience. If the bot is redeploying when a player joins, the mirror answers and the
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
 * managed to get" entry point, because an empty set is a legitimate "nobody is whitelisted" state
 * and could not be told apart from a partial failure after the fact.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Safe for concurrent use, and specifically so rather than by assertion. {@link MirrorEntry} is
 * immutable, and every mutation goes through {@code ConcurrentHashMap.compute} /
 * {@code computeIfPresent} so the read-modify-write on an expiry happens under the bin lock. The
 * login thread extending an entry and the scheduler reconciling it therefore serialise, instead of
 * interleaving into an expiry neither of them chose — and a reader never observes a half-updated
 * entry, which on the 32-bit JVMs the oldest supported servers run could otherwise mean a torn
 * {@code long}.
 *
 * <p><strong>This store does not own the scheduler it is handed.</strong> {@link #close()} flushes
 * and stops debouncing; it does not shut the scheduler down, because the caller shares it with
 * everything else Heimdall runs on a timer.
 *
 * @param <T> the mirrored value type; must be serialisable by Gson
 */
public final class MirrorStore<T> implements AutoCloseable {

    private final HeimdallLogger logger;
    private final MirrorPolicy policy;
    private final ConcurrentHashMap<String, MirrorEntry<T>> entries =
            new ConcurrentHashMap<String, MirrorEntry<T>>();
    private final MirrorFile<T> file;

    private volatile String lastEtag;

    /**
     * Starts describing a mirror to open.
     *
     * <p>A builder rather than a factory method because there were two of them, differing only in
     * whether the clock was injected, and a third argument would have meant a third overload. The
     * clock now lives on {@link MirrorPolicy}, where it belongs.
     *
     * @param path where the mirror is persisted; parent directories are created on first save
     * @param valueType the mirrored value's type, needed because Gson cannot see {@code T}
     */
    public static <T> Builder<T> builder(HeimdallLogger logger, Path path, Class<T> valueType) {
        return new Builder<T>(logger, path, valueType);
    }

    private MirrorStore(Builder<T> builder) {
        if (builder.logger == null || builder.path == null || builder.valueType == null) {
            throw new IllegalArgumentException("logger, path and valueType are required");
        }
        if (builder.policy == null) {
            throw new IllegalArgumentException("a policy is required — see MirrorPolicy.builder()");
        }
        if (builder.policy.saveDebounceMs() > 0 && builder.scheduler == null) {
            throw new IllegalArgumentException(
                    "a debounced mirror needs a scheduler; pass one, or set saveDebounceMs(0)");
        }
        this.logger = builder.logger;
        this.policy = builder.policy;
        this.file = new MirrorFile<T>(
                builder.logger, builder.path, builder.valueType, builder.policy.saveDebounceMs(),
                builder.scheduler,
                new Supplier<MirrorSnapshot<T>>() {
                    @Override
                    public MirrorSnapshot<T> get() {
                        return snapshot();
                    }
                });
        restore(file.load());
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
        if (policy.now() > effectiveExpiry(entry)) {
            entries.remove(key, entry);
            file.markDirty();
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
     * <p>Persisted with the entries so a restart resumes conditional polling instead of pulling a
     * full dump it already has.
     */
    public String lastEtag() {
        return lastEtag;
    }

    /** Records the ETag that accompanied the data currently mirrored. */
    public void setLastEtag(String etag) {
        this.lastEtag = etag;
        file.markDirty();
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /**
     * Records a value the bot has just confirmed.
     *
     * <p>This is a real verification: {@code lastVerified} moves to now, so the ceiling is measured
     * from now and the entry gets a full fresh window.
     */
    public void record(String key, final T value) {
        if (Strings.isBlank(key) || value == null) {
            logger.warn("Refusing to mirror an entry with a blank key or null value");
            return;
        }
        final long now = policy.now();
        // compute rather than put, for the same reason reconcile uses it: a verification racing
        // another verification must not be able to move lastVerified backward.
        entries.compute(key, new BiFunction<String, MirrorEntry<T>, MirrorEntry<T>>() {
            @Override
            public MirrorEntry<T> apply(String unused, MirrorEntry<T> existing) {
                if (existing == null) {
                    return new MirrorEntry<T>(value, now, now + policy.windowMs(), now);
                }
                return verify(existing, value, now, now + policy.windowMs());
            }
        });
        file.markDirty();
    }

    /**
     * <strong>Sets</strong> an existing entry's expiry to {@code now + windowMs}, capped by the
     * ceiling — on ordinary activity such as a join or a leave.
     *
     * <p>Set, not slide: if {@code windowMs} is shorter than what the entry already has, this
     * <em>shortens</em> it. That is v2's behaviour verbatim (a join extension of 120 minutes
     * landing after a leave extension of 180 minutes pulled the expiry back in), and it is kept for
     * parity rather than because it is obviously right. The plausible improvement — taking
     * {@code max(current, capped)} so activity can never reduce trust — is deliberately deferred
     * until 1d wires a real caller, so that a behaviour change and a first integration do not land
     * in the same commit. Recorded in {@code docs/v2-departures.md} as a known non-departure.
     *
     * <p><strong>Does not touch {@code lastVerified}</strong>, and is capped by the ceiling measured
     * from it. That is what stops activity alone from keeping a revoked value alive.
     *
     * <p>Does nothing for a key the mirror does not hold: activity is not evidence, so this can
     * never create an entry.
     *
     * @param windowMs how far ahead of now the new expiry sits, before capping
     * @return whether an entry was found and updated
     */
    public boolean extendOnEvent(String key, long windowMs) {
        if (key == null) {
            return false;
        }
        final long now = policy.now();
        final long proposed = now + Math.max(0, windowMs);
        // computeIfPresent, not get-then-mutate: the remapping runs under the bin lock, so a
        // reconcile landing at the same moment either happens entirely before or entirely after,
        // rather than interleaving into an expiry neither of them chose.
        MirrorEntry<T> updated = entries.computeIfPresent(key,
                new BiFunction<String, MirrorEntry<T>, MirrorEntry<T>>() {
                    @Override
                    public MirrorEntry<T> apply(String unused, MirrorEntry<T> entry) {
                        return entry.withActivity(now, cap(entry, proposed));
                    }
                });
        if (updated == null) {
            return false;
        }
        file.markDirty();
        return true;
    }

    /**
     * Replaces an entry's value without touching any of its timestamps.
     *
     * <p>For a detail nobody had to ask the bot about — v2 refreshed a player's username this way
     * on a cache hit. The distinction matters: {@link #record} is the only other way to change a
     * value, and it advances {@code lastVerified}, which would hand a revoked player a fresh
     * ceiling every time they changed their name. That would defeat the whole #771 bound.
     *
     * <p>Does nothing for a key the mirror does not hold.
     *
     * @return whether an entry was found and updated
     */
    public boolean touchValue(String key, final T value) {
        if (key == null || value == null) {
            return false;
        }
        MirrorEntry<T> existing = entries.get(key);
        if (existing == null) {
            return false;
        }
        if (value.equals(existing.value())) {
            // Nothing to write. Worth checking: this is called on every join, and marking the
            // mirror dirty for an unchanged username would defeat the debounce it sits behind.
            return true;
        }
        MirrorEntry<T> updated = entries.computeIfPresent(key,
                new BiFunction<String, MirrorEntry<T>, MirrorEntry<T>>() {
                    @Override
                    public MirrorEntry<T> apply(String unused, MirrorEntry<T> entry) {
                        return entry.withValue(value);
                    }
                });
        if (updated == null) {
            return false;
        }
        file.markDirty();
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
            throw new IllegalArgumentException(
                    "the authoritative set is required — pass an empty map, not null");
        }
        final long now = policy.now();
        Set<String> seen = new HashSet<String>();
        // Counted from inside the remapping function, which runs at most once per key here but is
        // not contractually single-shot, so the counters are atomic rather than plain ints.
        final AtomicInteger added = new AtomicInteger();
        final AtomicInteger updated = new AtomicInteger();

        for (Map.Entry<String, T> incoming : authoritative.entrySet()) {
            String key = incoming.getKey();
            final T value = incoming.getValue();
            if (Strings.isBlank(key) || value == null) {
                continue;
            }
            seen.add(key);

            entries.compute(key, new BiFunction<String, MirrorEntry<T>, MirrorEntry<T>>() {
                @Override
                public MirrorEntry<T> apply(String unused, MirrorEntry<T> existing) {
                    if (existing == null) {
                        added.incrementAndGet();
                        return new MirrorEntry<T>(value, now, now + policy.windowMs(), now);
                    }
                    updated.incrementAndGet();
                    // Refresh the window from this verification, but never shrink an entry a recent
                    // event already slid further forward.
                    return verify(existing, value, now,
                            Math.max(existing.cacheExpiry(), policy.cap(now + policy.windowMs(), now)));
                }
            });
        }

        int pruned = 0;
        for (String key : new HashSet<String>(entries.keySet())) {
            if (!seen.contains(key)) {
                entries.remove(key);
                pruned++;
            }
        }

        ReconcileResult result = new ReconcileResult(added.get(), updated.get(), pruned);
        file.markDirty();
        logger.info("Mirror reconcile (" + file.name() + "): " + result + " (" + entries.size() + " held)");
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
        long now = policy.now();
        int removed = 0;
        for (Map.Entry<String, MirrorEntry<T>> entry : entries.entrySet()) {
            if (now > effectiveExpiry(entry.getValue())) {
                entries.remove(entry.getKey(), entry.getValue());
                removed++;
            }
        }
        if (removed > 0) {
            file.markDirty();
            logger.debug("Swept " + removed + " expired mirror entries from " + file.name());
        }
        return removed;
    }

    /** Empties the mirror, ETag included — the next fetch must be a full one. */
    public void clear() {
        entries.clear();
        lastEtag = null;
        file.markDirty();
    }

    /** Writes any pending changes now. */
    public void flush() {
        file.flush();
    }

    /** Flushes and stops debouncing. Safe to call more than once. */
    @Override
    public void close() {
        file.close();
    }

    /** How many times the mirror has actually been written to disk. Diagnostics and tests. */
    long writeCount() {
        return file.writeCount();
    }

    /** The raw entry, expiry ignored — so tests can assert on the timestamps the rules produce. */
    MirrorEntry<T> rawEntry(String key) {
        return key == null ? null : entries.get(key);
    }

    /** A one-line summary for a status command. */
    public String stats() {
        long now = policy.now();
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
     * Applies a verification to an entry, keeping two things true that a race could otherwise break.
     *
     * <p>{@code lastVerified} never moves backward, and the resulting expiry is clamped to the
     * ceiling that {@code lastVerified} implies. Both are no-ops in the single-threaded case v2 was
     * written for, where {@code now} only ever increases — but two verifications in flight at once
     * can arrive out of order, and the older one would then lower the ceiling while the
     * never-shrink rule kept the newer, larger expiry. The result is an entry trusted past its own
     * bound, which is the one thing the #771 fix exists to prevent.
     */
    private MirrorEntry<T> verify(MirrorEntry<T> existing, T value, long now, long proposedExpiry) {
        long verifiedAt = Math.max(existing.lastVerified(), now);
        return existing.verified(value, verifiedAt, policy.cap(proposedExpiry, verifiedAt));
    }

    /** See {@link MirrorPolicy#effectiveExpiry} — the ceiling rule itself lives on the policy. */
    private long effectiveExpiry(MirrorEntry<T> entry) {
        return policy.effectiveExpiry(entry.cacheExpiry(), entry.lastVerified());
    }

    /** See {@link MirrorPolicy#cap}. */
    private long cap(MirrorEntry<T> entry, long proposedExpiry) {
        return policy.cap(proposedExpiry, entry.lastVerified());
    }

    /**
     * Loads a snapshot into the store, dropping anything unusable.
     *
     * <p><strong>If anything was dropped, the ETag goes with it.</strong> The ETag means "the
     * mirror holds exactly what the bot last sent", and a partial restore makes that false — but
     * the bot would keep answering 304 to it, so the missing entries would never come back and the
     * mirror would be permanently, silently short. Discarding the ETag costs one full dump on the
     * next poll and is the only way out.
     */
    private void restore(MirrorSnapshot<T> snapshot) {
        lastEtag = snapshot.etag;
        if (snapshot.entries == null) {
            return;
        }
        int dropped = 0;
        for (Map.Entry<String, MirrorEntry<T>> entry : snapshot.entries.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue().value() != null) {
                entries.put(entry.getKey(), entry.getValue());
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            lastEtag = null;
            logger.warn("Dropped " + dropped + " unusable entr" + (dropped == 1 ? "y" : "ies")
                    + " while loading " + file.name()
                    + " — discarding the stored ETag so the next poll refetches in full, rather "
                    + "than sitting on 304s against a mirror that is missing rows");
        }
        if (!entries.isEmpty()) {
            logger.info("Loaded " + entries.size() + " mirror entries from " + file.name());
        }
    }

    private MirrorSnapshot<T> snapshot() {
        // MirrorSnapshot copies the map itself, so this is already a point-in-time view.
        return new MirrorSnapshot<T>(lastEtag, entries);
    }

    /**
     * Describes a mirror before opening it.
     *
     * <p>{@link #open()} both constructs the store and loads the file, so nothing observes a
     * half-populated mirror.
     *
     * @param <T> the mirrored value type
     */
    public static final class Builder<T> {

        private final HeimdallLogger logger;
        private final Path path;
        private final Class<T> valueType;
        private MirrorPolicy policy = MirrorPolicy.builder().build();
        private ScheduledExecutorService scheduler;

        private Builder(HeimdallLogger logger, Path path, Class<T> valueType) {
            this.logger = logger;
            this.path = path;
            this.valueType = valueType;
        }

        /** Expiry rules, save debounce and clock. Defaults to {@code MirrorPolicy.builder().build()}. */
        public Builder<T> policy(MirrorPolicy value) {
            this.policy = value;
            return this;
        }

        /**
         * Where debounced saves run — typically {@code HeimdallExecutors.scheduler()}.
         *
         * <p>Required unless the policy sets {@code saveDebounceMs(0)}. <strong>The store does not
         * own it</strong> and will not shut it down.
         */
        public Builder<T> scheduler(ScheduledExecutorService value) {
            this.scheduler = value;
            return this;
        }

        /** Opens the mirror, loading whatever is already on disk. */
        public MirrorStore<T> open() {
            return new MirrorStore<T>(this);
        }
    }
}
