package com.heimdall.module.punishments;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
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
     * @param allowed lower-cased names the caller will accept, or {@code null} for all of them.
     *     {@code /unban} passes the players with an active ban, so completing it cannot offer
     *     somebody who is not banned.
     */
    List<String> matching(String prefix, int limit, Set<String> allowed) {
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
            int limit, Set<String> allowed, Map<String, String> out) {
        for (Map.Entry<String, String> entry : from.tailMap(needle).entrySet()) {
            if (out.size() >= limit) return;
            if (!entry.getKey().startsWith(needle)) {
                // Sorted, so the first key past the prefix ends the run.
                return;
            }
            if (allowed != null && !allowed.contains(entry.getKey())) continue;
            if (!out.containsKey(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
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
     * which is also where the names themselves are recorded - and read as two map lookups.
     *
     * <p>Volatile and replaced wholesale by {@link #install}: a sync reconciles the whole mirror
     * at once and its answer is authoritative, so it swaps a finished index in rather than
     * clearing and refilling one that a tab press could read half-built.
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
     * The lower-cased names with a live punishment of this family right now.
     *
     * <p>Expired rows are dropped as they are noticed rather than swept: nothing tells this index
     * that a tempban ran out, and a scheduled task for something read on a keystroke is machinery
     * nobody needs.
     */
    Set<String> withActive(String family, long nowMillis) {
        ConcurrentHashMap<String, Long> live = punished.get(family);
        if (live == null) {
            return Collections.emptySet();
        }
        Set<String> out = new HashSet<String>();
        for (Map.Entry<String, Long> entry : live.entrySet()) {
            if (entry.getValue().longValue() <= nowMillis) {
                live.remove(entry.getKey(), entry.getValue());
                continue;
            }
            out.add(entry.getKey());
        }
        return out;
    }

    /** A fresh index to fill and then {@link #install}. */
    static Index index() {
        return new Index();
    }

    /** Swaps a finished index in for the current one, in one assignment. */
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
