package com.heimdall.core.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.Strings;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * The bot's Minecraft API, one method per endpoint.
 *
 * <p><strong>Feature modules do not hold one of these.</strong> They reach the API through
 * {@link HeimdallApi}, which is the same client behind a gateway that answers usefully while this
 * server has no credentials or no guild yet. This class is core's, and core reconfigures it in
 * place rather than replacing it — see {@link #reconfigure} and departure D56.
 *
 * <h2>Threading contract</h2>
 *
 * <p>Every method returns immediately with a {@link CompletableFuture} and does its blocking work —
 * DNS, connect, read, and the retry sleeps — on the {@link Executor} handed to the constructor.
 * <strong>Nothing here ever touches the common {@code ForkJoinPool}</strong>: the executor-less
 * {@code *Async} overloads are banned by the conformance rules for exactly that reason, since the
 * common pool is shared with the server's own parallel work and is sized by core count, so on a
 * two-core VPS a handful of concurrent logins would starve it.
 *
 * <p>The client does <strong>not</strong> own that executor and never shuts it down — it is shared
 * with everything else Heimdall runs off-thread, and its lifecycle belongs to whoever created it.
 *
 * <h2>Bounding a blocking wait</h2>
 *
 * <p>A caller that blocks on a returned future must bound the wait on the budget <em>for that
 * endpoint</em> — not on a single timeout, and not on the login-path budget for all of them. Two
 * endpoints deliberately run with a much longer per-attempt timeout, so one number cannot cover
 * them:
 *
 * <table border="1">
 *   <caption>Which budget bounds which call</caption>
 *   <tr><th>Call</th><th>Wait at least</th></tr>
 *   <tr><td>{@link #connectionAttempt}, {@link #requestLinkCode}, {@link #offenseTypes},
 *       {@link #offend}</td><td>{@link ApiSettings#joinTimeoutMs()}</td></tr>
 *   <tr><td>{@link #whitelistSync}</td><td>{@link ApiSettings#whitelistSyncJoinTimeoutMs()}</td></tr>
 *   <tr><td>{@link #latestRelease}</td><td>{@link ApiSettings#updateCheckJoinTimeoutMs()}</td></tr>
 * </table>
 *
 * <p>Bounding a whitelist-sync wait on the login-path budget abandons the request about thirty
 * seconds early at the defaults, which is issue #797 / MC-6 wearing a different hat.
 *
 * <h2>Failures</h2>
 *
 * <p>Futures complete exceptionally with {@link ApiError} when the bot answered and refused, and
 * with {@link java.io.UncheckedIOException} when it could not be reached at all. The two are worth
 * distinguishing: only the second is a reason to consider failing open.
 *
 * <h2>Reconfiguration</h2>
 *
 * <p>{@link #reconfigure(ApiSettings)} swaps a whole immutable settings object, so an in-flight
 * request either signs entirely with the old configuration or entirely with the new one. v2 wrote
 * seven separate volatile fields one at a time.
 */
public final class ApiClient {

    private final HeimdallLogger logger;
    private final Executor executor;
    private final RequestExecutor requests;

    private volatile ApiSettings settings;
    private volatile BedrockIdentityProvider bedrockIdentity = BedrockIdentityProvider.NONE;

    /**
     * @param logger where transport diagnostics go
     * @param settings the initial configuration; swap it later with {@link #reconfigure}
     * @param executor the pool every request runs on — typically {@code HeimdallExecutors.io()}
     */
    public ApiClient(HeimdallLogger logger, ApiSettings settings, Executor executor) {
        if (logger == null || settings == null || executor == null) {
            throw new IllegalArgumentException("logger, settings and executor are all required");
        }
        this.logger = logger;
        this.settings = settings;
        this.executor = executor;
        this.requests = new RequestExecutor(logger);
    }

    /** The current settings. */
    public ApiSettings settings() {
        return settings;
    }

    /** Replaces the settings wholesale. Visible to in-flight workers immediately. */
    public void reconfigure(ApiSettings replacement) {
        if (replacement == null) {
            throw new IllegalArgumentException("settings are required");
        }
        this.settings = replacement;
        logger.debug(() -> "ApiClient reconfigured: " + replacement);
    }

    /**
     * Worst-case wall clock for one <strong>login-path</strong> request including retries — see
     * {@link ApiSettings#overallTimeoutMs()}. Reflects the most recent {@link #reconfigure}.
     *
     * <p>Does not cover {@link #whitelistSync} or {@link #latestRelease}; see the class javadoc for
     * which budget bounds which call.
     */
    public long getOverallTimeoutMs() {
        return settings.overallTimeoutMs();
    }

    /**
     * Installs the resolver used to enrich player-identifying requests with Bedrock identity.
     *
     * <p>Defaults to {@link BedrockIdentityProvider#NONE}. Platform modules install the reflective
     * Floodgate implementation when Floodgate is present.
     */
    public void setBedrockIdentityProvider(BedrockIdentityProvider provider) {
        this.bedrockIdentity = provider == null ? BedrockIdentityProvider.NONE : provider;
    }

    // ── Endpoints ────────────────────────────────────────────────────────────

    /**
     * Resolves this server's guild from its API key.
     *
     * <p>The one endpoint that is <strong>not</strong> guild-scoped, because a plugin calling it does
     * not yet know its guild — which is the entire point. The bot authenticates it inline, ahead of
     * the middleware that would otherwise want a guild in the path.
     *
     * <p>{@code bootstrap.yml} deliberately has no {@code guildId} to configure: an operator pasting
     * a token should not also have to find a snowflake, and a guild id typed in by hand that
     * disagrees with the token produces a server that signs perfectly and reads somebody else's
     * configuration. The token knows which guild it belongs to, so the token is asked.
     *
     * <p>{@code X-Token-Id} rides along when the bootstrap carries one. It is optional: the
     * signature is what authenticates, and a token issued before that field existed still has to be
     * able to resolve.
     *
     * @return the guild id, never blank — a blank answer raises {@link ApiError} rather than
     *     resolving to an empty guild that every later path would sign requests for
     */
    public CompletableFuture<String> identify() {
        return async(() -> {
            ApiSettings current = settings;
            HttpCall call = HttpCall.post("/api/minecraft/identify", "{}", current.timeoutMs());
            if (Strings.isNotBlank(current.tokenId())) {
                call = call.withHeader("X-Token-Id", current.tokenId());
            }
            return ApiResponses.identify(requests.execute(current, call));
        });
    }

    /** {@code POST connection-attempt} — should this player be let in, and what should they be told? */
    public CompletableFuture<ConnectionAttemptResult> connectionAttempt(ConnectionAttempt attempt) {
        if (attempt == null) {
            throw new IllegalArgumentException("attempt is required");
        }
        return async(() -> {
            ApiSettings current = settings;
            JsonObject body = new JsonObject();
            body.addProperty("username", attempt.username());
            body.addProperty("uuid", attempt.uuid());
            body.addProperty("ip", attempt.ip());
            body.addProperty("serverIp", attempt.serverIp());
            body.addProperty("serverId", current.serverId());
            body.addProperty("currentlyWhitelisted", attempt.currentlyWhitelisted());
            JsonArray groups = new JsonArray();
            for (String group : attempt.currentGroups()) {
                groups.add(group);
            }
            body.add("currentGroups", groups);
            addBedrockIdentity(body, attempt.uuid());

            return ApiResponses.connectionAttempt(requests.execute(current,
                    HttpCall.post(guildPath(current, "connection-attempt"), body.toString(),
                            current.timeoutMs())));
        });
    }

    /** {@code POST request-link-code} — mint a linking code, or report an existing link. */
    public CompletableFuture<LinkCodeResult> requestLinkCode(String username, String uuid) {
        if (Strings.isBlank(username) || Strings.isBlank(uuid)) {
            throw new IllegalArgumentException("username and uuid are required");
        }
        return async(() -> {
            ApiSettings current = settings;
            JsonObject body = new JsonObject();
            // Verbatim, not lower-cased. link.ts writes minecraftUsername straight from this body,
            // so v2's normalisation rewrote every linked player's name in the bot's database.
            body.addProperty("username", username.trim());
            body.addProperty("uuid", uuid.trim());
            addBedrockIdentity(body, uuid.trim());

            return ApiResponses.linkCode(requests.execute(current,
                    HttpCall.post(guildPath(current, "request-link-code"), body.toString(),
                            current.timeoutMs())));
        });
    }

    /** {@code GET offense-types} — the configured offense categories, for tab completion and display. */
    public CompletableFuture<List<OffenseType>> offenseTypes() {
        return async(() -> {
            ApiSettings current = settings;
            return ApiResponses.offenseTypes(requests.execute(current,
                    HttpCall.get(guildPath(current, "offense-types"), current.timeoutMs())));
        });
    }

    /** {@code POST offend} — record an offense and receive the escalated punishment to apply. */
    public CompletableFuture<OffenseResult> offend(OffenseReport report) {
        if (report == null) {
            throw new IllegalArgumentException("report is required");
        }
        return async(() -> {
            ApiSettings current = settings;
            JsonObject body = new JsonObject();
            body.addProperty("targetUuid", report.targetUuid());
            body.addProperty("targetUsername", report.targetUsername());
            body.addProperty("offenseSlug", report.offenseSlug());
            addIfPresent(body, "issuedByUuid", report.issuedByUuid());
            addIfPresent(body, "issuedByUsername", report.issuedByUsername());
            addIfPresent(body, "notes", report.notes());

            return ApiResponses.offense(requests.execute(current,
                    HttpCall.post(guildPath(current, "offend"), body.toString(), current.timeoutMs())));
        });
    }

    /**
     * {@code GET whitelist/sync} — the full whitelist, or a 304 saying it has not changed.
     *
     * @param etag the ETag from the previous successful fetch, or {@code null} to force a full dump.
     *     Pass {@link WhitelistSyncResult#etag()} straight back; quoting is handled by the bot,
     *     which compares after stripping quotes.
     */
    public CompletableFuture<WhitelistSyncResult> whitelistSync(String etag) {
        return async(() -> {
            ApiSettings current = settings;
            HttpCall call = HttpCall.get(
                    guildPath(current, "whitelist/sync"), current.whitelistSyncTimeoutMs());
            if (Strings.isNotBlank(etag)) {
                call = call.withHeader("If-None-Match", etag);
            }
            return ApiResponses.whitelistSync(requests.execute(current, call));
        });
    }

    /**
     * {@code POST …/servers/{serverId}/config/import} — hand the dashboard a v2 config, once.
     *
     * <p>The only route under {@code servers/} a guild-scoped Minecraft token may call; every other
     * one there is the dashboard's. It is <strong>write-once</strong>, so calling it against a
     * server that already has a document answers {@code 200} with {@code imported: false} and
     * changes nothing — which is what makes it safe to call unconditionally from the first-boot
     * migration without asking permission first.
     *
     * <p>There is deliberately no {@code 404} for an unregistered server: the document is simply
     * inert until a registry row points at it, which is exactly the state a server that has migrated
     * but not yet run {@code /hd setup} is in.
     *
     * @param serverId the server the settings belong to — this server's own id
     * @param modules the {@code modules} document, in the same shape a {@code config.push} carries
     */
    public CompletableFuture<ConfigImportResult> importConfig(String serverId, Payload modules) {
        if (Strings.isBlank(serverId)) {
            throw new IllegalArgumentException("a serverId is required to import config for");
        }
        final Payload document = modules == null ? Payload.empty() : modules;
        final String id = serverId.trim();
        return async(() -> {
            ApiSettings current = settings;
            JsonObject body = new JsonObject();
            body.add("modules", JsonParser.parseString(document.toJson()));
            return ApiResponses.configImport(requests.execute(current,
                    HttpCall.post(guildPath(current, "servers/" + id + "/config/import"),
                            body.toString(), current.timeoutMs())));
        });
    }

    /** {@code GET plugin/latest} — the newest published release, for the update check. */
    public CompletableFuture<PluginRelease> latestRelease() {
        return async(() -> {
            ApiSettings current = settings;
            return ApiResponses.pluginRelease(requests.execute(current,
                    HttpCall.get(guildPath(current, "plugin/latest"), current.updateCheckTimeoutMs())));
        });
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * The one place a task is handed to the executor.
     *
     * <p>{@code supplyAsync(Supplier, Executor)} — the two-argument overload — every time. The
     * one-argument one silently uses the common pool, and the conformance suite fails the build if
     * it appears anywhere under {@code com.heimdall}.
     */
    private <T> CompletableFuture<T> async(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, executor);
    }

    private static String guildPath(ApiSettings settings, String suffix) {
        return "/api/guilds/" + settings.guildId() + "/minecraft/" + suffix;
    }

    /**
     * Merges Bedrock identity into a request body, if the joining player has one.
     *
     * <p>A no-op for Java players and for servers without Floodgate, in which case the bot falls
     * back to inferring Bedrock from the synthetic UUID and stripping its configured prefix.
     */
    private void addBedrockIdentity(JsonObject body, String uuid) {
        BedrockIdentity identity;
        try {
            identity = bedrockIdentity.resolve(uuid);
        } catch (RuntimeException e) {
            // A misbehaving identity provider must not cost a player their login.
            logger.warn("Bedrock identity lookup failed for " + uuid + ": " + e);
            return;
        }
        if (identity == null || Strings.isBlank(identity.gamertag())) {
            return;
        }
        body.addProperty("isBedrock", Boolean.TRUE);
        body.addProperty("bedrockGamertag", identity.gamertag());
        addIfPresent(body, "bedrockXuid", identity.xuid());
    }

    private static void addIfPresent(JsonObject body, String key, String value) {
        if (Strings.isNotBlank(value)) {
            body.addProperty(key, value);
        }
    }
}
