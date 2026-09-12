package com.heimdall.module.punishments;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * The player names {@code /hd ban} and its siblings can complete, and the order they come in.
 *
 * <h2>Why an index rather than asking at completion time</h2>
 *
 * <p>Tab completion runs on a keystroke, on whichever thread the platform hands it to - the main
 * server thread on the Bukkit family, a proxy worker on Velocity. Two things follow. It cannot ask
 * the bot, because a network round trip on the tick loop is the whole reason
 * {@code CommandCompleter} says what it says. And it cannot read the online roster either:
 * {@link com.heimdall.core.platform.PlayerDirectory#onlinePlayers()} is explicitly allowed to
 * throw rather than claim the server is empty, and the Bukkit implementation does exactly that
 * when the snapshot is taken off the main thread.
 *
 * <p>So the names are pushed in as they are learned - a join, a quit, a punishment sync, an apply
 * - and completion is a read of two concurrent maps. Nothing here blocks, takes a lock, or touches
 * a platform type.
 *
 * <h2>Online first, then alphabetical</h2>
 *
 * <p>Two maps rather than one set with a flag: the answer to "who do I mean" is almost always
 * somebody who is online right now, and an alphabetical list of everyone this proxy has ever seen
 * buries them. Both maps are sorted by the lower-cased name, so each group comes out
 * alphabetically and a prefix scan stops at the first key past the prefix rather than walking the
 * whole set. A name in both appears once.
 *
 * <h2>Bounded</h2>
 *
 * <p>Nothing prunes the last-address file, so on a busy server the seen-set is the only thing here
 * that can grow without limit. It stops accepting new names at {@link #SEEN_LIMIT}. Losing the
 * ten-thousand-and-first name costs a moderator one tab press; an unbounded map costs the server
 * memory it never agreed to spend on autocomplete.
 */
final class KnownNames {

    /** The most names the seen-set will hold before it stops accepting new ones. */
    static final int SEEN_LIMIT = 10_000;

    /** Lower-cased name to the spelling to show, for everyone online right now. */
    private final ConcurrentSkipListMap<String, String> online =
            new ConcurrentSkipListMap<String, String>();

    /** The same, for everyone this instance has evidence of having seen. */
    private final ConcurrentSkipListMap<String, String> seen =
            new ConcurrentSkipListMap<String, String>();

    /** {@link ConcurrentSkipListMap#size()} walks the whole map, so the bound is counted instead. */
    private final AtomicInteger seenCount = new AtomicInteger();

    /** Records somebody who is online now. Also counts as seen. */
    void joined(String name) {
        String key = key(name);
        if (key.isEmpty()) return;
        online.put(key, name.trim());
        remember(name);
    }

    /** Forgets somebody who left. They stay in the seen-set. */
    void quit(String name) {
        String key = key(name);
        if (key.isEmpty()) return;
        online.remove(key);
    }

    /** Records a name this instance has evidence of: a last-IP row, a punishment, an apply. */
    void remember(String name) {
        String key = key(name);
        if (key.isEmpty() || seen.containsKey(key)) {
            return;
        }
        if (seenCount.get() >= SEEN_LIMIT) {
            return;
        }
        if (seen.put(key, name.trim()) == null) {
            seenCount.incrementAndGet();
        }
    }

    void rememberAll(Collection<String> names) {
        if (names == null) return;
        for (String name : names) {
            remember(name);
        }
    }

    /** Whether this name is one of the online ones. Case-insensitive. */
    boolean isOnline(String name) {
        String key = key(name);
        return !key.isEmpty() && online.containsKey(key);
    }

    /**
     * The names to offer for a partial word, online players first and alphabetically within each
     * group.
     *
     * @param prefix what has been typed so far, possibly empty
     * @param limit the most names to return
     * @param allowed asked of each lower-cased name the prefix scan reaches, or {@code null} to
     *     accept every one. A predicate rather than a set on purpose: {@code /unban} filters to
     *     the players with an active ban, and a server with twenty thousand of them would
     *     otherwise build a twenty-thousand-entry set on the main thread for every keystroke, to
     *     answer at most {@code limit} questions of it. See {@link #withActive}.
     */
    List<String> matching(String prefix, int limit, Predicate<String> allowed) {
        String needle = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        // Keyed by the lower-cased name rather than by the spelling: the same player can be
        // online as "Steve" and recorded on an old punishment as "steve", and offering both is
        // offering a choice that does not exist.
        Map<String, String> out = new LinkedHashMap<String, String>();
        collect(online, needle, limit, allowed, out);
        collect(seen, needle, limit, allowed, out);
        return new ArrayList<String>(out.values());
    }

    private static void collect(ConcurrentNavigableMap<String, String> from, String needle,
            int limit, Predicate<String> allowed, Map<String, String> out) {
        for (Map.Entry<String, String> entry : from.tailMap(needle).entrySet()) {
            if (out.size() >= limit) return;
            if (!entry.getKey().startsWith(needle)) {
                // Sorted, so the first key past the prefix ends the run.
                return;
            }
            // Already offered by the online pass, so there is nothing to decide and no reason to
            // ask the filter a second time about the same name.
            if (out.containsKey(entry.getKey())) continue;
            if (allowed != null && !allowed.test(entry.getKey())) continue;
            out.put(entry.getKey(), entry.getValue());
        }
    }

    // ── Who has something to lift ───────────────────────────────────────────────────────────

    /**
     * A punishment that does not end, as this index stores an end.
     *
     * <p>A sentinel rather than a nullable value, so the expiry comparison in {@link #withActive}
     * is one branch and a permanent ban cannot be read as an expired one by a caller that forgot.
     */
    static final long NEVER = Long.MAX_VALUE;

    /**
     * Lower-cased name to when their punishment of that family ends, per revoke family.
     *
     * <p><strong>An index rather than a question asked at completion time.</strong> This is what
     * {@code /unban}, {@code /unmute} and {@code /unwarn} filter their name list with, and
     * completion runs on a keystroke, on the main server thread on the Bukkit family. The previous
     * answer walked every key in the punishment mirror and did a lookup per key, per tab press,
     * per revoke verb. It is written at the handful of moments a punishment lands or is lifted -
     * which is also where the names themselves are recorded.
     *
     * <p><strong>Read one name at a time, never in bulk.</strong> {@link #withActive} hands back a
     * predicate over this map rather than a copy of its keys, so a tab press costs one lookup per
     * name the prefix scan actually reaches - at most {@code limit} of them - instead of a set the
     * size of every active punishment on the server.
     *
     * <p>Volatile and replaced wholesale by {@link #install}: a sync reconciles the whole mirror
     * at once and its answer is authoritative, so it swaps a finished index in rather than
     * clearing and refilling one that a tab press could read half-built.
     *
     * <p><strong>The swap is not lossless, and is not meant to be.</strong> A punishment that
     * lands between the start of the rebuild's mirror walk and the {@link #install} writes into
     * the map that is about to be discarded, so that one name is missing from the family until
     * something touches it again: the next event for that player, or the next sync, which is four
     * minutes away at worst. The window is one mirror walk every four minutes, the cost inside it
     * is one moderator's tab press offering one name fewer, and it heals without anybody doing
     * anything. Journalling writes across the rebuild to close it would add a second piece of
     * concurrent state to a class whose whole point is that reading it is free.
     */
    private volatile Map<String, ConcurrentHashMap<String, Long>> punished = emptyFamilies();

    /** Records that this player has something of this family to lift. */
    void punished(String family, String name, long endsAtMillis) {
        ConcurrentHashMap<String, Long> live = punished.get(family);
        String key = key(name);
        if (live == null || key.isEmpty()) return;
        live.put(key, Long.valueOf(endsAtMillis));
    }

    /** Forgets a lifted punishment. The name itself stays known. */
    void unpunished(String family, String name) {
        ConcurrentHashMap<String, Long> live = punished.get(family);
        String key = key(name);
        if (live == null || key.isEmpty()) return;
        live.remove(key);
    }

    /**
     * Whether a lower-cased name has a live punishment of this family, as a predicate.
     *
     * <p>A predicate over the live map, not a snapshot of it. The caller is
     * {@link #matching}'s prefix scan, which stops after {@code limit} names, so asking one
     * question per candidate reached costs a handful of hash lookups where copying the family's
     * keys cost an allocation proportional to every active punishment on the server - on a
     * keystroke, on the main thread.
     *
     * <p>Expired rows are dropped as the predicate reaches them rather than swept: nothing tells
     * this index that a tempban ran out, and a scheduled task for something read on a keystroke is
     * machinery nobody needs. One that no completion ever reaches survives until the next sync
     * rebuilds the index, which skips expired rows, so nothing accumulates.
     *
     * <p>The map is captured once, when the predicate is made. A sync that swaps a rebuilt index
     * in mid-completion therefore finishes that one tab press against the index it started with,
     * which is the consistent answer rather than a half-old one.
     */
    Predicate<String> withActive(String family, long nowMillis) {
        final ConcurrentHashMap<String, Long> live = punished.get(family);
        if (live == null) {
            return NOBODY;
        }
        final long now = nowMillis;
        return new Predicate<String>() {
            @Override
            public boolean test(String key) {
                Long ends = live.get(key);
                if (ends == null) {
                    return false;
                }
                if (ends.longValue() <= now) {
                    live.remove(key, ends);
                    return false;
                }
                return true;
            }
        };
    }

    /** The answer for a family nobody can be punished in. */
    private static final Predicate<String> NOBODY = new Predicate<String>() {
        @Override
        public boolean test(String key) {
            return false;
        }
    };

    /** A fresh index to fill and then {@link #install}. */
    static Index index() {
        return new Index();
    }

    /**
     * Swaps a finished index in for the current one, in one assignment.
     *
     * <p>Anything written to the outgoing index while this one was being built is discarded with
     * it, and recovers on the next write for that name or the next sync. See the
     * {@link #punished} field for why that is the trade taken.
     */
    void install(Index index) {
        if (index == null) return;
        this.punished = index.byFamily;
    }

    /** The whole live-punishment index, under construction. See {@link #install}. */
    static final class Index {

        private final Map<String, ConcurrentHashMap<String, Long>> byFamily = emptyFamilies();

        private Index() {
        }

        Index add(String family, String name, long endsAtMillis) {
            ConcurrentHashMap<String, Long> live = byFamily.get(family);
            String key = key(name);
            if (live == null || key.isEmpty()) return this;
            Long existing = live.get(key);
            // The longest of a player's rows in one family decides, because /unban lifts them
            // together: a permanent ipban beside an expiring ban is still something to lift.
            if (existing == null || existing.longValue() < endsAtMillis) {
                live.put(key, Long.valueOf(endsAtMillis));
            }
            return this;
        }
    }

    /**
     * The three families, each with an empty set.
     *
     * <p>Fixed rather than created on demand, so a lookup for a family nobody has been punished in
     * is a hit on an empty map instead of a null the two writers and the reader each have to
     * remember. A type outside the three ({@code kick}, {@code geo}, {@code subnet}) has no revoke
     * verb that names a player and is not represented here at all.
     */
    private static Map<String, ConcurrentHashMap<String, Long>> emptyFamilies() {
        Map<String, ConcurrentHashMap<String, Long>> families =
                new LinkedHashMap<String, ConcurrentHashMap<String, Long>>();
        families.put("ban", new ConcurrentHashMap<String, Long>());
        families.put("mute", new ConcurrentHashMap<String, Long>());
        families.put("warn", new ConcurrentHashMap<String, Long>());
        return Collections.unmodifiableMap(families);
    }

    /** How many distinct names are known, online or not. For tests and diagnostics. */
    int size() {
        Set<String> all = new LinkedHashSet<String>(seen.keySet());
        all.addAll(online.keySet());
        return all.size();
    }

    static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
