package com.heimdall.module.offenses;

import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.http.model.OffenseType;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.Lists;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The offense types the bot is configured with, kept in memory so tab completion never asks.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>{@code CommandCompleter.complete} runs on a server thread on <em>every keystroke</em> of a tab
 * press. A completer that called {@code GET …/offense-types} would put a signed HTTP round trip —
 * with retries, so tens of seconds in the worst case — on the tick loop, once per character. So the
 * network read happens on a timer and the completer is a filter over a list that is already here.
 * That is v2's design too ({@code OffenseManager}); what is new is that the refresh is a tracked
 * {@code ModuleContext.scheduleRepeating}, so switching the module off really stops it.
 *
 * <h2>A failed refresh keeps the previous answer</h2>
 *
 * <p>{@link #refresh()} never completes exceptionally and never throws — it logs and leaves the
 * cache exactly as it was. That is v2 parity ({@code OffenseManager.refresh} swallowed and logged)
 * and it is the right behaviour rather than an accident: a bot that is briefly unreachable should
 * cost an operator the freshness of their tab completion, not the whole of it. The alternative —
 * clearing on failure — turns one dropped request into "no offense exists", and the operator's next
 * move is to type the slug by hand and be told it is unknown.
 *
 * <p>The same property is why the refresh is scheduled through {@code heimdall-sched} and not
 * chained off the previous one: a task that dies on its first transient error and never runs again
 * is the failure mode {@code ModuleContextImpl.guard} exists to prevent, and this method not
 * throwing means it can never be triggered from here.
 *
 * <h2>Threading</h2>
 *
 * <p>Thread-safe by construction. The cached list is swapped wholesale through an
 * {@link AtomicReference} holding an already-unmodifiable list, so a reader either sees the whole
 * previous set or the whole new one — never a list being cleared and refilled underneath it, which
 * is what v2's {@code clear()}-then-{@code addAll()} on a {@code CopyOnWriteArrayList} exposed a
 * tab-completing operator to.
 *
 * <p>{@link #refresh()} does no work on the calling thread: {@link HeimdallApi} runs the request on
 * {@code heimdall-io} and the completion callback is a plain (non-{@code Async}) {@code
 * whenComplete}, so it runs on whichever thread completed the request — {@code heimdall-io} — and
 * never on the caller's.
 */
public final class OffenseTypeCache {

    private final HeimdallLogger logger;

    /**
     * The bot's API, as the gateway every module sees.
     *
     * <p>Never {@code null}, in any state. Until 1e this was a raw client that <em>was</em> null on
     * a server that had never been set up, captured once at registration — so a server claimed with
     * {@code /hd setup} kept a null here forever and this cache never refreshed until a restart.
     * Departure D56 is the whole story; the visible half is that this reference stays correct across
     * a setup.
     */
    private final HeimdallApi api;

    private final AtomicReference<List<OffenseType>> cached =
            new AtomicReference<List<OffenseType>>(Collections.<OffenseType>emptyList());

    private volatile long lastRefreshMillis;

    OffenseTypeCache(HeimdallLogger logger, HeimdallApi api) {
        if (logger == null || api == null) {
            throw new IllegalArgumentException("logger and api are required");
        }
        this.logger = logger;
        this.api = api;
    }

    /**
     * Every cached type, enabled or not.
     *
     * <p>Unfiltered on purpose: {@code /hd offense types} lists the disabled ones too,
     * marked as disabled, because "the type exists but staff may not report against it" and "no such
     * type" are different answers and an operator debugging a missing slug needs to tell them apart.
     * {@link #matchingSlugs(String)} is the filtered view, and it is what the command offers.
     *
     * @return an immutable snapshot; never {@code null}
     */
    public List<OffenseType> types() {
        return cached.get();
    }

    /** When the cache was last successfully replaced, as epoch millis; {@code 0} if never. */
    public long lastRefreshMillis() {
        return lastRefreshMillis;
    }

    /** Whether anything has ever been loaded — what {@code /hd offense types} branches on. */
    public boolean hasCachedData() {
        return !cached.get().isEmpty();
    }

    /**
     * Slugs beginning with {@code prefix}, from <strong>enabled</strong> types only, sorted.
     *
     * <p>Enabled-only because offering a slug the bot will answer {@code 404 UNKNOWN_OFFENSE} for is
     * worse than offering nothing: the operator finds out after the punishment did not land.
     *
     * <p>Matched case-insensitively on both sides. v2 lower-cased only the prefix and compared it
     * against the slug as stored, which happened to work because the bot lower-cases slugs — a
     * dependency on the far side's normalisation that nothing states and nothing checks.
     *
     * @param prefix the partial word; {@code null} or empty means "everything"
     * @return an immutable, sorted, de-duplicated list; never {@code null}
     */
    public List<String> matchingSlugs(String prefix) {
        String lower = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<String>();
        for (OffenseType type : cached.get()) {
            if (!type.enabled()) {
                continue;
            }
            for (String slug : type.offenses()) {
                if (slug == null) {
                    continue;
                }
                String normalised = slug.trim().toLowerCase(Locale.ROOT);
                // Two types may legitimately name the same slug; offering it twice is a visible
                // defect in a tab-completion list and the duplicate carries no information.
                if (!normalised.isEmpty() && normalised.startsWith(lower)
                        && !matches.contains(normalised)) {
                    matches.add(normalised);
                }
            }
        }
        Collections.sort(matches);
        return Collections.unmodifiableList(matches);
    }

    /**
     * Re-reads the types from the bot.
     *
     * <p>Returns as soon as the request is handed to {@code heimdall-io}; the returned future
     * completes — <strong>always normally</strong> — when the attempt has finished either way. A
     * caller that wants to know whether it worked compares {@link #lastRefreshMillis()} across the
     * call; nothing in the plugin currently needs to.
     *
     * @return a future that completes when the attempt is over, and never completes exceptionally
     */
    public CompletableFuture<Void> refresh() {
        final CompletableFuture<Void> attempt = new CompletableFuture<Void>();
        if (!api.isUsable()) {
            // Checked rather than caught: the gateway would refuse this call anyway, but a refusal
            // per five-minute tick on an unconfigured server is a debug line per tick for a state
            // that is entirely normal.
            logger.debug("not refreshing offense types: the bot cannot be asked yet ("
                    + api.describe() + ")");
            attempt.complete(null);
            return attempt;
        }
        // Plain whenComplete, not whenCompleteAsync: the executor-less *Async overloads route onto
        // the common ForkJoinPool and the conformance rules reject them. The non-async form runs on
        // the thread that completed the request, which is already heimdall-io.
        api.offenseTypes().whenComplete((fetched, failure) -> {
            try {
                if (failure != null) {
                    recordFailure(failure);
                } else {
                    recordSuccess(fetched);
                }
            } finally {
                // In a finally, because a logger that throws must not leave a caller — the module's
                // /hd offense reload path — waiting on a future that will never complete.
                attempt.complete(null);
            }
        });
        return attempt;
    }

    private void recordSuccess(List<OffenseType> fetched) {
        replaceAll(fetched);
        lastRefreshMillis = System.currentTimeMillis();
        logger.info("Loaded " + cached.get().size() + " offense types ("
                + slugCount() + " offense slugs)");
    }

    private void recordFailure(Throwable failure) {
        // warn, not error: an unreachable bot is a degraded feature, and this runs every five
        // minutes. A stack trace per attempt would bury the rest of the log during an outage.
        logger.warn("Failed to refresh offense types: " + Failures.describe(failure)
                + " — keeping the " + cached.get().size() + " already cached");
    }

    /**
     * Swaps the whole cache.
     *
     * <p>Package-private rather than private so the tests can pin the filtering rules — enabled-only,
     * sorted, case-insensitive, de-duplicated — against types the stub bot's fixture does not carry,
     * without inventing a second way to reach the bot. Nothing in production calls it but
     * {@link #refresh()}.
     */
    void replaceAll(List<OffenseType> types) {
        cached.set(Lists.copyOf(types));
    }

    private int slugCount() {
        int total = 0;
        for (OffenseType type : cached.get()) {
            total += type.offenses().size();
        }
        return total;
    }
}
