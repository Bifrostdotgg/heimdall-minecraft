package com.heimdall.core.http;

import com.google.gson.JsonObject;
import com.heimdall.core.http.model.ClaimResult;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.Strings;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The one call a server makes before it has any credentials: {@code POST /api/minecraft/claim}.
 *
 * <h2>Why this is not a method on {@link ApiClient}</h2>
 *
 * <p>Two reasons, and both would have made {@code ApiClient} worse.
 *
 * <p><strong>The endpoint is an argument, not configuration.</strong> {@code ApiClient} holds one
 * immutable {@link ApiSettings} carrying the base URL it signs against, and swapping that in place
 * to make one call would be observable by every request in flight. A claim, by contrast, is
 * routinely made against a URL the operator has just typed — a whitelabel instance keeps its setup
 * codes in its own database, so its customers claim against their instance rather than against
 * {@code api.bifrost.gg}, and that URL is not known until the command is run.
 *
 * <p><strong>It is unsigned.</strong> Every other route on {@code ApiClient} carries an HMAC, and a
 * client that has a public method which does not is a client somebody will eventually call for the
 * wrong route. The signature is skipped through {@code HttpCall.unsignedPost} rather than through a
 * second transport, so departure D20's single request path still holds.
 *
 * <h2>It does not retry, and that is the important part</h2>
 *
 * <p>{@link ApiSettings#retries()} is pinned to 1 here. A setup code is single-use and the bot
 * consumes it atomically, so a retry after a response we failed to read would present a code the
 * bot has already spent — turning a transient network hiccup into "that setup code is invalid",
 * with the real credentials lost on the bot's side and no way to ask for them again. Failing the
 * first attempt and telling the operator to mint a new code is the honest outcome; quietly burning
 * their code and then reporting it as invalid is not.
 *
 * <p>The bot's own rate limit makes the same point from the other side: ten failures from one
 * client and it answers {@code 429} to everything, whatever the body says.
 *
 * <h2>Threading</h2>
 *
 * <p>Returns immediately; the blocking work runs on the supplied {@link Executor}, which this class
 * borrows and never shuts down. The future completes exceptionally with {@link ApiError} when the
 * bot answered and refused — {@code INVALID_CODE}, {@code MISSING_PARAMS},
 * {@code TOO_MANY_ATTEMPTS} — and with {@link java.io.UncheckedIOException} when it could not be
 * reached at all. Callers must tell those apart: only the second is worth retrying by hand.
 */
public final class ClaimClient {

    /**
     * Per-attempt timeout for a claim.
     *
     * <p>Longer than the login path's five seconds, because nobody is waiting on a tick loop for it
     * and the operator who just typed a code would rather wait than be told to try again — and
     * short enough that a wrong endpoint fails while they are still looking at the console.
     */
    public static final int TIMEOUT_MS = 15_000;

    private final HeimdallLogger logger;
    private final Executor executor;
    private final RequestExecutor requests;

    /**
     * @param executor the pool the request blocks on — {@code HeimdallExecutors.io()} in production.
     *     Borrowed, never shut down.
     */
    public ClaimClient(HeimdallLogger logger, Executor executor) {
        if (logger == null || executor == null) {
            throw new IllegalArgumentException("logger and executor are required");
        }
        this.logger = logger;
        this.executor = executor;
        this.requests = new RequestExecutor(logger);
    }

    /**
     * Exchanges a setup code for this server's credentials.
     *
     * @param endpoint the bot's base URL, with or without a trailing slash
     * @return the credentials, exactly once — see {@link ClaimResult}
     */
    public CompletableFuture<ClaimResult> claim(final String endpoint, final ClaimRequest request) {
        if (Strings.isBlank(endpoint)) {
            throw new IllegalArgumentException("an endpoint is required to claim against");
        }
        if (request == null) {
            throw new IllegalArgumentException("a claim request is required");
        }
        final ApiSettings settings = ApiSettings.builder()
                .baseUrl(endpoint)
                .timeoutMs(TIMEOUT_MS)
                .retries(1)
                .build();
        return CompletableFuture.supplyAsync(new java.util.function.Supplier<ClaimResult>() {
            @Override
            public ClaimResult get() {
                JsonObject body = new JsonObject();
                body.addProperty("code", request.code());
                addIfPresent(body, "platform", request.platform());
                addIfPresent(body, "mcVersion", request.mcVersion());
                if (request.role() != null) {
                    body.addProperty("role", request.role().wireName());
                }
                logger.debug(() -> "claiming a setup code against " + settings.baseUrl());
                return ApiResponses.claim(requests.execute(settings,
                        HttpCall.unsignedPost("/api/minecraft/claim", body.toString(), TIMEOUT_MS)));
            }
        }, executor);
    }

    private static void addIfPresent(JsonObject body, String key, String value) {
        if (Strings.isNotBlank(value)) {
            body.addProperty(key, value);
        }
    }
}
