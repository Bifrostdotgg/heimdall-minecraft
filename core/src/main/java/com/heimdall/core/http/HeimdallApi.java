package com.heimdall.core.http;

import com.heimdall.core.http.model.ConfigImportResult;
import com.heimdall.core.http.model.ConnectionAttempt;
import com.heimdall.core.http.model.ConnectionAttemptResult;
import com.heimdall.core.http.model.LinkCodeResult;
import com.heimdall.core.http.model.OffenseReport;
import com.heimdall.core.http.model.OffenseResult;
import com.heimdall.core.http.model.OffenseType;
import com.heimdall.core.http.model.PluginRelease;
import com.heimdall.core.http.model.WhitelistSyncResult;
import com.heimdall.core.json.Payload;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * How a feature module reaches the bot: always present, and honest about when it cannot.
 *
 * <h2>The problem this exists to close (departure D56)</h2>
 *
 * <p>Until 1e, three feature modules took an {@code ApiClient} as a constructor argument and each
 * tolerated {@code null} — a server that was never set up has no client to hand them. That works
 * right up to the moment {@code /hd setup} configures a server <em>without a restart</em>: the
 * modules captured their reference once, at registration, long before any of this existed, so they
 * would go on holding {@code null} forever and {@code /offend} and {@code /linkdiscord} would keep
 * refusing on a server that was demonstrably connected. Nothing re-hands them a live client, and
 * nothing could, because the reference lived in a field on each of them.
 *
 * <p>So there is exactly one of these per plugin, it is created before anything is registered, and
 * it is never replaced. Setup and guild discovery reconfigure the {@link ApiClient} underneath it in
 * place — which is what guild discovery has always done and the reason it never had this problem —
 * so a module that captured this object at enable is still holding the right thing an hour later.
 *
 * <h2>Three states, and the two that are not failures</h2>
 *
 * <p>{@link #availability()} is derived from the settings rather than tracked separately, so it
 * cannot disagree with what the next request would actually do:
 *
 * <ul>
 *   <li>{@link Availability#NOT_CONFIGURED} — no endpoint or no token. A fresh install.
 *   <li>{@link Availability#DISCOVERING} — credentials, but {@code identify} has not answered yet,
 *       so there is no guild to put in a path. Transient, and every configured server passes
 *       through it (departure D54).
 *   <li>{@link Availability#READY} — go.
 * </ul>
 *
 * <p>In the first two, every endpoint below returns a future that is <em>already</em> completed
 * exceptionally with an {@link ApiUnavailableException} carrying which one. Nothing is sent. That
 * matters beyond tidiness: without a guild the client builds
 * {@code /api/guilds//minecraft/connection-attempt}, and a warm mirror on a restarting server turns
 * that into one signed, malformed, guaranteed-404 request per returning player.
 *
 * <p>A caller that would rather branch than catch asks {@link #isUsable()} first — which is what the
 * whitelist interceptor does, because "no guild yet" is a reason to run the configured fallback
 * mode rather than a reason to report an error.
 *
 * <h2>Threading</h2>
 *
 * <p>Safe from any thread. Every method returns immediately; the blocking work happens on the pool
 * the underlying {@link ApiClient} was given, which this class neither owns nor shuts down. The
 * failure semantics are the client's: {@link ApiError} when the bot answered and refused,
 * {@link java.io.UncheckedIOException} when it could not be reached, and
 * {@link ApiUnavailableException} when it was never asked.
 */
public final class HeimdallApi {

    /** Whether the bot can be asked, and if not, why not. */
    public enum Availability {

        /** Credentials and a guild. Requests go out. */
        READY,

        /** No {@code bootstrap.yml}, or one with no endpoint or no token. Run {@code /hd setup}. */
        NOT_CONFIGURED,

        /** Credentials, but the guild has not come back from {@code identify} yet. Transient. */
        DISCOVERING
    }

    private final ApiClient client;

    /**
     * @param client the one client this plugin has. Reconfigured in place by core as the server is
     *     set up and as its guild resolves; never replaced, which is the whole point.
     */
    public HeimdallApi(ApiClient client) {
        if (client == null) {
            throw new IllegalArgumentException("a client is required");
        }
        this.client = client;
    }

    // ── State ────────────────────────────────────────────────────────────────

    /**
     * Whether the bot can be asked, derived from the settings currently in force.
     *
     * <p>Derived rather than stored on purpose. A tracked flag is a second source of truth that has
     * to be updated everywhere the settings are, and the failure when somebody forgets is a gateway
     * that cheerfully sends a request against a guild it does not have.
     */
    public Availability availability() {
        ApiSettings settings = client.settings();
        if (settings.baseUrl().isEmpty() || settings.apiKey().isEmpty()) {
            return Availability.NOT_CONFIGURED;
        }
        if (settings.guildId().isEmpty()) {
            return Availability.DISCOVERING;
        }
        return Availability.READY;
    }

    /** Whether a request made right now would actually be sent. */
    public boolean isUsable() {
        return availability() == Availability.READY;
    }

    /**
     * The settings in force — timeouts, budgets, the server id.
     *
     * <p>Never {@code null}, and readable in every state: a caller bounding a blocking wait needs
     * {@link ApiSettings#whitelistSyncJoinTimeoutMs()} whether or not the request will be sent.
     */
    public ApiSettings settings() {
        return client.settings();
    }

    /** One short phrase for a status line, naming the state rather than the mechanism. */
    public String describe() {
        switch (availability()) {
            case READY:
                return "ready (guild " + client.settings().guildId() + ")";
            case DISCOVERING:
                return "discovering this token's guild";
            case NOT_CONFIGURED:
            default:
                return "not set up";
        }
    }

    // ── Endpoints ────────────────────────────────────────────────────────────

    /** {@code POST connection-attempt} — should this player be let in? */
    public CompletableFuture<ConnectionAttemptResult> connectionAttempt(final ConnectionAttempt attempt) {
        return gated(() -> client.connectionAttempt(attempt));
    }

    /** {@code POST request-link-code} — mint a Discord linking code. */
    public CompletableFuture<LinkCodeResult> requestLinkCode(final String username, final String uuid) {
        return gated(() -> client.requestLinkCode(username, uuid));
    }

    /** {@code GET offense-types} — the configured offense categories. */
    public CompletableFuture<List<OffenseType>> offenseTypes() {
        return gated(() -> client.offenseTypes());
    }

    /** {@code POST offend} — record an offense and receive the escalated punishment. */
    public CompletableFuture<OffenseResult> offend(final OffenseReport report) {
        return gated(() -> client.offend(report));
    }

    /** {@code GET whitelist/sync} — the full whitelist, or a 304. */
    public CompletableFuture<WhitelistSyncResult> whitelistSync(final String etag) {
        return gated(() -> client.whitelistSync(etag));
    }

    /** {@code GET plugin/latest} — the newest published release. */
    public CompletableFuture<PluginRelease> latestRelease() {
        return gated(() -> client.latestRelease());
    }

    /** {@code POST …/servers/{id}/config/import} — hand the dashboard a migrated v2 config, once. */
    public CompletableFuture<ConfigImportResult> importConfig(final String serverId, final Payload modules) {
        return gated(() -> client.importConfig(serverId, modules));
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Runs {@code call} if the bot can be asked, and fails the future immediately if it cannot.
     *
     * <p>The supplier is not invoked in the unusable states, so a request body is never even built —
     * which is what keeps a mirror hit on a guild-less server from costing anything at all.
     */
    private <T> CompletableFuture<T> gated(Supplier<CompletableFuture<T>> call) {
        Availability state = availability();
        if (state == Availability.READY) {
            return call.get();
        }
        CompletableFuture<T> refused = new CompletableFuture<T>();
        refused.completeExceptionally(new ApiUnavailableException(state, explain(state)));
        return refused;
    }

    private static String explain(Availability state) {
        if (state == Availability.DISCOVERING) {
            return "this server has not resolved its guild yet; the bot cannot be asked until it has";
        }
        return "this server is not set up — run /hd setup <code> to connect it to Discord";
    }
}
