package com.heimdall.module.offenses;

import com.heimdall.core.admin.OffenseAdmin;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.model.OffenseType;
import com.heimdall.core.module.HeimdallModule;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.util.Registration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Punishments: {@code /offend}, the offense-type cache behind its tab completion, and nothing else.
 *
 * <h2>What it owns</h2>
 *
 * <ul>
 *   <li>{@code /offend <player> <offense> [notes]}, registered through {@link
 *       ModuleContext#registerCommand} so switching the module off really takes it away (D53, which
 *       is D30 applied to commands). See {@link OffendCommand}.
 *   <li>An {@link OffenseTypeCache} refreshed on enable and every {@value #REFRESH_INTERVAL_MS}
 *       milliseconds thereafter, so tab completion is a list filter rather than an HTTP round trip
 *       on the tick loop.
 * </ul>
 *
 * <h2>It claims no capability, deliberately</h2>
 *
 * <p>{@link #capabilities()} is empty because there is no {@code offenses@N} in {@code
 * Capabilities}, and inventing one would be worse than claiming nothing. The bot's accepted table is
 * fixed; an identifier it does not know is silently dropped from the {@code identify} declaration,
 * so the module would look correct in testing and be ignored in production — the exact failure the
 * constants in {@code Capabilities} exist to prevent. The consequence of claiming nothing is that
 * the bot does not narrow a {@code config.push} to this module, and the module has no settings to
 * read anyway: everything configurable about offenses — the types, the tiers, the commands — lives
 * bot-side and arrives in the {@code offend} response. If offenses ever grow a settings section, the
 * capability is added to {@code Capabilities} and to the bot's table in the same change, not here
 * alone.
 *
 * <h2>It runs under any role</h2>
 *
 * <p>{@link #roles()} is empty. Role eligibility is about who owns the login decision; reporting an
 * offense is not a login decision, and an operator standing on a backend server must be able to
 * punish somebody without hopping to the proxy.
 *
 * <h2>The API arrives through the context</h2>
 *
 * <p>It used to be a constructor argument that was {@code null} on a server nobody had set up. That
 * is what made {@code /hd setup} unable to work without a restart: the reference was captured once,
 * at registration, and nothing could re-hand a live one — so a freshly claimed server had a working
 * tunnel and an {@code /offend} that still refused. Since 1e it is {@link ModuleContext#api()},
 * which is one stable gateway core re-points underneath (departure D56). It is never {@code null};
 * it answers "not set up" instead, which the command reports rather than throwing on.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #enable} and {@link #disable} run on the reconciliation thread and are never concurrent
 * for this module. Both are quick: {@code enable} registers two things and hands the first refresh
 * to {@code heimdall-io} without waiting for it, and {@code disable} closes two handles.
 *
 * <p>{@link #cachedTypes()} and {@link #refreshOffenseTypes()} are safe from any thread; the admin
 * tree calls them from {@code heimdall-io} behind {@code /hd offense}.
 */
public final class HeimdallOffensesModule implements HeimdallModule, OffenseAdmin {

    /** The module's stable identifier, used for config keys and logging. */
    public static final String ID = "offenses";

    /**
     * How often the offense types are re-read, in milliseconds.
     *
     * <p>Five minutes: the data changes when a staff member edits it in the dashboard, which is
     * rare, and the cost of being stale is a tab-completion list that is briefly missing a
     * newly-added slug — the operator can still type it, and the bot resolves it. Polling harder
     * would spend a signed round trip per server per minute on a fleet to shorten a window nobody
     * notices.
     */
    public static final long REFRESH_INTERVAL_MS = 5L * 60L * 1000L;

    /**
     * The cache the current enable built, or {@code null} while the module is stopped.
     *
     * <p>Rebuilt on each enable rather than kept across one. Serving whatever the module happened to
     * hold when an operator switched it off is worse than serving nothing for the second it takes
     * the enable-time refresh to land, because the stale list is indistinguishable from a fresh one.
     */
    private volatile OffenseTypeCache cache;

    private volatile Registration command = Registration.NONE;
    private volatile Registration refresh = Registration.NONE;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> capabilities() {
        return Collections.emptySet();
    }

    @Override
    public Set<ServerRole> roles() {
        return Collections.emptySet();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void enable(ModuleContext context) {
        final OffenseTypeCache types = new OffenseTypeCache(context.logger(), context.api());
        this.cache = types;

        if (!context.api().isUsable()) {
            context.logger().warn("the bot cannot be asked yet (" + context.api().describe()
                    + "), so /offend will refuse to record anything until it can");
        }

        OffendCommand offend = new OffendCommand(
                context.logger(),
                context.api(),
                types,
                context.platform().players(),
                context.platform().console());
        this.command = context.registerCommand(offend.spec());

        // The first refresh is NOT the scheduler's: an operator who enables the module should get a
        // usable tab-completion list now rather than in five minutes. It returns immediately —
        // ApiClient does the blocking work on heimdall-io — so enable() stays quick, as a config
        // push is waiting on it.
        this.refresh = context.scheduleRepeating(new Runnable() {
            @Override
            public void run() {
                types.refresh();
            }
        }, REFRESH_INTERVAL_MS, REFRESH_INTERVAL_MS);
        types.refresh();
    }

    /**
     * Closes both handles and drops the cache.
     *
     * <p>Everything here is already unwound mechanically by {@code ModuleManager} (D30), so this is
     * belt and braces rather than the mechanism — but it is what makes the module correct when it is
     * driven directly, and closing a {@link Registration} twice is a no-op by contract.
     *
     * <p>Safe after a failed {@link #enable}: both fields start at {@link Registration#NONE} and are
     * assigned in order, so a partial enable leaves the ones it reached closable and the rest inert.
     */
    @Override
    public void disable() {
        Registration scheduled = refresh;
        refresh = Registration.NONE;
        scheduled.close();

        Registration registered = command;
        command = Registration.NONE;
        registered.close();

        cache = null;
    }

    // ── OffenseAdmin: what /hd offense reaches ───────────────────────────────

    @Override
    public boolean isAvailable() {
        return cache != null;
    }

    /**
     * Blocking form of {@link #refreshOffenseTypes()}, for the admin command.
     *
     * <p>Bounded rather than a bare {@code join()}. The future completes on {@code heimdall-io} and
     * this is called from {@code heimdall-io}, which is a fixed pool of four — an unbounded wait
     * there is one thread of a four-thread pool held indefinitely by a bot that stopped answering,
     * and four operators being impatient at once would be the whole pool.
     */
    @Override
    public void reload() {
        try {
            refreshOffenseTypes().get(RELOAD_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            // Cleared rather than restored: this runs on a shared pool thread, and leaving it
            // interrupted breaks the next, unrelated task that lands on it.
            Thread.interrupted();
        } catch (Exception failed) {
            // The refresh never completes exceptionally by contract, so this is a timeout — which
            // the caller reports by showing the (unchanged) list rather than by claiming success.
            Thread.interrupted();
        }
    }

    @Override
    public List<OffenseType> types() {
        return cachedTypes();
    }

    /** How long {@link #reload()} waits. Comfortably past the login budget's worst case. */
    private static final long RELOAD_WAIT_MS = 30_000L;

    // ── The wider surface, for callers that want a future ────────────────────

    /**
     * Re-reads the offense types from the bot — what {@code /hd offense reload} calls.
     *
     * <p>The consumer is {@link #reload()}, which is what the admin command reaches; nothing else
     * needs it, because the module already refreshes itself on enable and on a timer.
     *
     * <p>Never completes exceptionally: a failed refresh leaves the previous cache intact and logs.
     * A caller that wants to show a result compares {@link #cachedTypes()} across the call.
     *
     * @return a future that completes when the attempt is over, whichever way it went. A module that
     *     is not enabled completes immediately, having done nothing.
     */
    public CompletableFuture<Void> refreshOffenseTypes() {
        OffenseTypeCache types = cache;
        return types == null ? CompletableFuture.<Void>completedFuture(null) : types.refresh();
    }

    /**
     * Every cached offense type, enabled and disabled alike — what {@code /hd offense types} lists.
     *
     * <p>Unfiltered on purpose: "the type exists but is switched off" and "no such type" are
     * different answers, and an operator wondering why a slug will not complete needs to tell them
     * apart. {@code /offend}'s own completion offers enabled types only.
     *
     * @return an immutable snapshot; empty while the module is disabled or before the first refresh
     *     has landed
     */
    public List<OffenseType> cachedTypes() {
        OffenseTypeCache types = cache;
        return types == null ? Collections.<OffenseType>emptyList() : types.types();
    }
}
