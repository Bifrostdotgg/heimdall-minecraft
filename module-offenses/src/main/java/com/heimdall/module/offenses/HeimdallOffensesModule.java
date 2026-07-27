package com.heimdall.module.offenses;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.ApiClient;
import com.heimdall.core.http.model.OffenseType;
import com.heimdall.core.module.HeimdallModule;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.util.Registration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
 * <h2>The {@link ApiClient} arrives through the constructor</h2>
 *
 * <p>{@link ModuleContext} does not expose one — core owns the client, and on a server that was
 * never set up there is not one to expose. Rather than widening the context or the platform facade
 * for a single module, the client is a constructor argument, and {@code null} is a supported value:
 * the command answers "this server is not set up yet" and the refresh is a logged no-op. A module
 * that could not load without credentials would leave a fresh install unable to run the setup flow
 * that supplies them.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #enable} and {@link #disable} run on the reconciliation thread and are never concurrent
 * for this module. Both are quick: {@code enable} registers two things and hands the first refresh
 * to {@code heimdall-io} without waiting for it, and {@code disable} closes two handles.
 *
 * <p>{@link #cachedTypes()} and {@link #refreshOffenseTypes()} are safe from any thread — phase 1e
 * calls them from a command handler on a server thread.
 */
public final class HeimdallOffensesModule implements HeimdallModule {

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

    private final ApiClient api;

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

    /**
     * @param api the bot's API, or {@code null} on a server that has not been set up — see the class
     *     javadoc. The orchestrator constructs this module and supplies whatever {@code
     *     HeimdallRuntime.api()} currently holds.
     */
    public HeimdallOffensesModule(ApiClient api) {
        this.api = api;
    }

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
        final OffenseTypeCache types = new OffenseTypeCache(context.logger(), api);
        this.cache = types;

        if (api == null) {
            context.logger().warn("this server is not set up yet, so /offend will refuse to record "
                    + "anything until it is");
        }

        OffendCommand offend = new OffendCommand(
                context.logger(),
                api,
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

    // ── The surface phase 1e consumes ────────────────────────────────────────

    /**
     * Re-reads the offense types from the bot — what {@code /hd offense reload} calls.
     *
     * <p>The consumer is phase 1e's admin command; nothing else needs it, because the module already
     * refreshes itself on enable and on a timer.
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
