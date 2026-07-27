package com.heimdall.stubbot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The HTTP half of the fixture — the six endpoints the Java plugin calls, behind real HMAC
 * verification.
 *
 * <p>Bound on loopback with an ephemeral port; {@link PortMultiplexer} fronts it so HTTP and the
 * WebSocket upgrade share one public port, exactly as they do on the real bot.
 */
final class StubHttpApi {

    /** Matches the bot's own mount point: {@code /api/guilds/:guildId/minecraft/…}. */
    private static final Pattern GUILD_ROUTE =
            Pattern.compile("^/api/guilds/(\\d{17,20})/minecraft/(.+)$");

    /** ISO-8601 with fixed millisecond precision, matching JavaScript's {@code toISOString()}. */
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /**
     * {@code serializeNulls} is not cosmetic here. Gson drops explicit nulls by default, which would
     * silently turn {@code "roleSync": null} and {@code "duration": null} into <em>absent</em> keys —
     * and the plugin distinguishes the two: an absent {@code roleSync} and a null one mean the same
     * thing today, but an absent {@code duration} on a tempban tier would read as "no duration
     * field, use the default" rather than "explicitly unbounded". The bot sends the nulls; so do we.
     */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final StubBotConfig config;
    private final FixtureStore fixtures;
    private final HttpServer server;
    private final ExecutorService executor;

    /**
     * Non-pardoned infraction counts, keyed {@code guildId|uuid|typeId}. The escalation maths needs
     * a running total to be worth anything — a stub that always answers "tier 1" would let a plugin
     * that mishandles escalation pass.
     */
    private final Map<String, AtomicInteger> infractionCounts = new ConcurrentHashMap<>();

    StubHttpApi(StubBotConfig config, FixtureStore fixtures) {
        this.config = config;
        this.fixtures = fixtures;
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "stub-bot-http-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("could not bind the stub HTTP server", e);
        }
        server.setExecutor(executor);
        server.createContext("/", this::dispatch);
    }

    void start() {
        server.start();
    }

    void stop() {
        server.stop(0);
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** Clears the escalation counters, so a test can replay {@code /offend} from a known state. */
    void resetInfractions() {
        infractionCounts.clear();
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            String body = readBody(exchange);

            // Express signs over `req.originalUrl` — path AND query string. Reconstructing it from
            // the raw components rather than URI.toString() keeps any percent-encoding the client
            // sent byte-identical, which matters because the signature covers these exact bytes.
            URI uri = exchange.getRequestURI();
            String rawPath = uri.getRawPath();
            String signedPath = uri.getRawQuery() == null ? rawPath : rawPath + "?" + uri.getRawQuery();

            String signature = exchange.getRequestHeaders().getFirst("X-Signature");
            String timestamp = exchange.getRequestHeaders().getFirst("X-Timestamp");

            if ("/api/minecraft/identify".equals(rawPath)) {
                handleIdentify(exchange, method, signedPath, body, signature, timestamp);
                return;
            }

            if (!Hmac.verify(config.apiKey(), method, signedPath, body, signature, timestamp)) {
                StubLog.debug("HMAC rejected " + method + " " + signedPath
                        + " (sig=" + (signature == null ? "missing" : "present")
                        + ", ts=" + (timestamp == null ? "missing" : timestamp) + ")");
                sendUnauthorized(exchange);
                return;
            }

            Matcher matcher = GUILD_ROUTE.matcher(rawPath);
            if (!matcher.matches()) {
                sendError(exchange, 404, "NOT_FOUND", "No route for " + method + " " + rawPath);
                return;
            }
            String guildId = matcher.group(1);
            String route = matcher.group(2);

            if (!config.guildId().equals(guildId)) {
                // What the real bot answers when the guild has no Minecraft config row.
                sendError(exchange, 404, "NOT_CONFIGURED", "Minecraft integration not enabled");
                return;
            }

            StubLog.debug(method + " " + signedPath);

            switch (method + " " + route) {
                case "POST connection-attempt" -> handleConnectionAttempt(exchange, body);
                case "GET whitelist/sync" -> handleWhitelistSync(exchange);
                case "POST request-link-code" -> handleRequestLinkCode(exchange, body);
                case "GET offense-types" -> sendEnvelope(exchange, 200, config.offenseTypes());
                case "POST offend" -> handleOffend(exchange, guildId, body);
                case "GET plugin/latest" -> sendEnvelope(exchange, 200, config.pluginLatest());
                default -> sendError(exchange, 404, "NOT_FOUND", "No route for " + method + " " + route);
            }
        } catch (RuntimeException e) {
            StubLog.warn("handler threw: " + e);
            sendError(exchange, 500, "INTERNAL_ERROR", String.valueOf(e.getMessage()));
        } finally {
            exchange.close();
        }
    }

    // ── Endpoints ────────────────────────────────────────────────────────────

    /**
     * {@code POST /api/minecraft/identify} — resolves an API key to a guild id, so the plugin can be
     * configured with a token alone. Authenticates inline (the caller does not yet know the guild),
     * which is why it sits ahead of the HMAC gate.
     */
    private void handleIdentify(
            HttpExchange exchange, String method, String signedPath, String body, String signature, String timestamp)
            throws IOException {
        if (signature == null || timestamp == null) {
            sendError(exchange, 401, "UNAUTHORIZED", "Missing auth headers");
            return;
        }
        if (!Hmac.verify(config.apiKey(), method, signedPath, body, signature, timestamp)) {
            sendError(exchange, 401, "UNAUTHORIZED", "No matching guild found for this API key");
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("guildId", config.guildId());
        sendEnvelope(exchange, 200, data);
    }

    private void handleConnectionAttempt(HttpExchange exchange, String body) throws IOException {
        JsonObject request = parseObject(body);
        String username = optString(request, "username");
        String uuid = optString(request, "uuid");

        if (isBlank(username) || isBlank(uuid)) {
            sendError(exchange, 400, "MISSING_FIELDS", "username and uuid are required");
            return;
        }

        PlayerFixture fixture = fixtures.find(uuid);
        Outcome outcome = fixture == null ? fixtures.defaultOutcome() : fixture.outcome();
        JsonObject data = new JsonObject();

        switch (outcome) {
            case ALLOW -> {
                data.addProperty("whitelisted", true);
                data.addProperty("message", message(fixture, "§aWelcome back, {player}!", username, null));
                data.add("roleSync", roleSync(fixture));
            }
            case DENY -> {
                data.addProperty("whitelisted", false);
                data.addProperty("message", message(fixture,
                        "§cYou are not whitelisted. Use /link-minecraft or /minecraft-status in Discord to get started.",
                        username, null));
            }
            case PENDING_AUTH -> {
                String code = authCode(fixture, uuid);
                data.addProperty("whitelisted", false);
                data.addProperty("message", message(fixture,
                        "§eYour authentication code is: §6{code}\n"
                                + "§7Go back to Discord and click §fConfirm Code §7to complete linking.",
                        username, code));
                data.addProperty("pendingAuth", true);
                data.addProperty("authCode", code);
            }
            case REVOKED -> {
                String reason = fixture == null || fixture.revocationReason() == null
                        ? "" : fixture.revocationReason();
                data.addProperty("whitelisted", false);
                data.addProperty("message", message(fixture,
                        "§cYour whitelist has been revoked{reason}.\n"
                                + "§7Please contact staff for more information.",
                        username, null).replace("{reason}", reason));
                data.addProperty("revoked", true);
            }
            case PENDING_APPROVAL -> {
                int position = fixture == null || fixture.queuePosition() == null ? 1 : fixture.queuePosition();
                data.addProperty("whitelisted", false);
                data.addProperty("message", message(fixture,
                        "§eYour whitelist application is pending staff approval.\n"
                                + "§7Please wait for a staff member to review your request.\n"
                                + "§7You are §b#{position}§7 in the queue.",
                        username, null).replace("{position}", String.valueOf(position)));
                data.addProperty("pendingApproval", true);
                data.addProperty("queuePosition", position);
            }
            case EXISTING_LINK -> {
                String code = authCode(fixture, uuid);
                data.addProperty("whitelisted", true);
                data.addProperty("message", message(fixture,
                        "§eLink your Discord account!\n"
                                + "§eUse §6/confirm-code {code}§e in Discord\n"
                                + "§eCode expires in 15 minutes.",
                        username, code));
                data.addProperty("existingPlayerLink", true);
                data.addProperty("authCode", code);
            }
            default -> throw new IllegalStateException("unhandled outcome " + outcome);
        }

        sendEnvelope(exchange, 200, data);
    }

    /**
     * {@code roleSync} for an allowed player.
     *
     * <p>Three shapes, all of which the plugin must handle: {@code null} (nothing to apply — a row
     * with no snapshot yet), {@code {enabled: false}} (the bot is driving LuckPerms over RCON, so
     * keep out), and the full {@code {enabled: true, targetGroups, managedGroups}}.
     */
    private JsonElement roleSync(PlayerFixture fixture) {
        if (fixture == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        Boolean enabled = fixture.roleSyncEnabled();
        if (Boolean.FALSE.equals(enabled)) {
            JsonObject disabled = new JsonObject();
            disabled.addProperty("enabled", false);
            return disabled;
        }
        List<String> target = fixture.targetGroups();
        if (target == null && !Boolean.TRUE.equals(enabled)) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        JsonObject sync = new JsonObject();
        sync.addProperty("enabled", true);
        sync.add("targetGroups", toArray(target == null ? List.of() : target));
        sync.add("managedGroups", toArray(fixture.managedGroups()));
        return sync;
    }

    private void handleWhitelistSync(HttpExchange exchange) throws IOException {
        List<PlayerFixture> whitelisted = fixtures.whitelistedPlayers();
        List<String> uuids = new ArrayList<>();
        JsonArray players = new JsonArray();
        for (PlayerFixture fixture : whitelisted) {
            uuids.add(fixture.uuid());
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", fixture.uuid());
            if (fixture.username() == null) {
                entry.add("username", com.google.gson.JsonNull.INSTANCE);
            } else {
                entry.addProperty("username", fixture.username());
            }
            players.add(entry);
        }
        String hash = FixtureStore.etag(uuids);

        // The bot compares after stripping quotes, so both `"abc"` and `abc` match. A plugin that
        // echoes the header verbatim and one that unwraps it both have to work.
        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        exchange.getResponseHeaders().set("ETag", "\"" + hash + "\"");
        if (ifNoneMatch != null && ifNoneMatch.replace("\"", "").equals(hash)) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty("hash", hash);
        data.addProperty("count", players.size());
        data.addProperty("generatedAt", ISO_MILLIS.format(Instant.now()));
        data.add("players", players);
        sendEnvelope(exchange, 200, data);
    }

    private void handleRequestLinkCode(HttpExchange exchange, String body) throws IOException {
        JsonObject request = parseObject(body);
        String username = optString(request, "username");
        String uuid = optString(request, "uuid");

        if (isBlank(username) || isBlank(uuid)) {
            sendError(exchange, 400, "MISSING_FIELDS", "username and uuid are required");
            return;
        }

        PlayerFixture fixture = fixtures.find(uuid);
        JsonObject data = new JsonObject();

        if (fixture != null && fixture.linkedDiscordId() != null) {
            List<String> parts = new ArrayList<>();
            if (fixture.linkedDiscordDisplayName() != null) {
                parts.add(fixture.linkedDiscordDisplayName());
            }
            if (fixture.linkedDiscordUsername() != null) {
                parts.add("(@" + fixture.linkedDiscordUsername() + ")");
            }
            parts.add("[ID: " + fixture.linkedDiscordId() + "]");

            data.addProperty("alreadyLinked", true);
            data.addProperty("message",
                    "Your Minecraft account is already linked to " + String.join(" ", parts) + ".");
            addNullable(data, "discordDisplayName", fixture.linkedDiscordDisplayName());
            addNullable(data, "discordUsername", fixture.linkedDiscordUsername());
            addNullable(data, "discordId", fixture.linkedDiscordId());
        } else {
            data.addProperty("alreadyLinked", false);
            data.addProperty("code", authCode(fixture, uuid));
        }

        sendEnvelope(exchange, 200, data);
    }

    /**
     * {@code POST /offend} — records an offense and resolves the escalation tier.
     *
     * <p>The tier maths is transcribed from the bot rather than faked: the lowest tier whose
     * {@code points} is at least the new total wins, falling back to the highest tier once the
     * player runs off the end. Placeholders are resolved in the same order too — {@code {reason}}
     * first, so a command template can embed the resolved reason.
     */
    private void handleOffend(HttpExchange exchange, String guildId, String body) throws IOException {
        JsonObject request = parseObject(body);
        String targetUuid = optString(request, "targetUuid");
        String targetUsername = optString(request, "targetUsername");
        String offenseSlug = optString(request, "offenseSlug");

        if (isBlank(targetUuid) || isBlank(targetUsername) || isBlank(offenseSlug)) {
            sendError(exchange, 400, "MISSING_FIELDS",
                    "targetUuid, targetUsername, and offenseSlug are required.");
            return;
        }

        String slug = offenseSlug.toLowerCase(Locale.ROOT);
        JsonObject offenseType = findOffenseType(slug);
        if (offenseType == null) {
            sendError(exchange, 404, "UNKNOWN_OFFENSE",
                    "No enabled offense type found for slug '" + offenseSlug + "'.");
            return;
        }

        List<JsonObject> tiers = new ArrayList<>();
        for (JsonElement element : offenseType.getAsJsonArray("escalationTiers")) {
            tiers.add(element.getAsJsonObject());
        }
        if (tiers.isEmpty()) {
            sendError(exchange, 400, "NO_TIERS",
                    "Offense type '" + offenseType.get("typeId").getAsString()
                            + "' has no escalation tiers configured.");
            return;
        }
        tiers.sort((a, b) -> Integer.compare(a.get("points").getAsInt(), b.get("points").getAsInt()));

        String typeId = offenseType.get("typeId").getAsString();
        String key = guildId + "|" + targetUuid.toLowerCase(Locale.ROOT) + "|" + typeId;
        int newTotal = infractionCounts.computeIfAbsent(key, unused -> new AtomicInteger()).incrementAndGet();

        JsonObject tier = tiers.get(tiers.size() - 1);
        int tierIndex = tiers.size();
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i).get("points").getAsInt() >= newTotal) {
                tier = tiers.get(i);
                tierIndex = i + 1;
                break;
            }
        }

        Integer duration = tier.has("duration") && !tier.get("duration").isJsonNull()
                ? tier.get("duration").getAsInt() : null;
        String durationText = formatDuration(duration);

        Map<String, String> vars = new java.util.LinkedHashMap<>();
        vars.put("player", targetUsername);
        vars.put("offense", offenseType.get("displayName").getAsString());
        vars.put("points", String.valueOf(newTotal));
        vars.put("tier", String.valueOf(tierIndex));
        vars.put("duration", durationText);
        vars.put("reason", "");

        String resolvedReason = resolvePlaceholders(tier.get("reason").getAsString(), vars);
        vars.put("reason", resolvedReason);
        String resolvedCommand = resolvePlaceholders(tier.get("command").getAsString(), vars);

        JsonObject infraction = new JsonObject();
        infraction.addProperty("_id", String.format("%024x", Math.abs((long) key.hashCode()) * 31L + newTotal));
        infraction.addProperty("guildId", guildId);
        infraction.addProperty("minecraftUuid", targetUuid);
        infraction.addProperty("minecraftUsername", targetUsername);
        infraction.addProperty("offenseTypeId", typeId);
        infraction.addProperty("offenseSlug", slug);
        infraction.addProperty("points", 1);
        infraction.addProperty("totalPointsAtTime", newTotal);
        infraction.addProperty("tierApplied", tierIndex);
        infraction.addProperty("action", tier.get("action").getAsString());
        if (duration != null) {
            infraction.addProperty("duration", duration);
        }
        infraction.addProperty("reason", resolvedReason);
        infraction.addProperty("command", resolvedCommand);
        infraction.addProperty("pardoned", false);

        JsonObject data = new JsonObject();
        data.add("infraction", infraction);
        data.addProperty("command", resolvedCommand);
        data.addProperty("action", tier.get("action").getAsString());
        if (duration == null) {
            data.add("duration", com.google.gson.JsonNull.INSTANCE);
        } else {
            data.addProperty("duration", duration);
        }
        data.addProperty("totalPoints", newTotal);
        data.addProperty("tierApplied", tierIndex);
        data.addProperty("tierDescription", tier.get("action").getAsString()
                + (duration != null ? " (" + durationText + ")" : ""));
        data.addProperty("offenseType", offenseType.get("displayName").getAsString());

        sendEnvelope(exchange, 200, data);
    }

    private JsonObject findOffenseType(String slug) {
        for (JsonElement element : config.offenseTypes()) {
            JsonObject type = element.getAsJsonObject();
            if (type.has("enabled") && !type.get("enabled").getAsBoolean()) {
                continue;
            }
            for (JsonElement offense : type.getAsJsonArray("offenses")) {
                if (offense.getAsString().equalsIgnoreCase(slug)) {
                    return type;
                }
            }
        }
        return null;
    }

    /** {@code 60 → "1h"}, {@code 1440 → "1d"}, {@code 90 → "1h30m"} — the bot's own formatter. */
    static String formatDuration(Integer minutes) {
        if (minutes == null || minutes <= 0) {
            return "";
        }
        int days = minutes / 1440;
        int hours = (minutes % 1440) / 60;
        int mins = minutes % 60;
        StringBuilder out = new StringBuilder();
        if (days > 0) {
            out.append(days).append('d');
        }
        if (hours > 0) {
            out.append(hours).append('h');
        }
        if (mins > 0) {
            out.append(mins).append('m');
        }
        return out.length() == 0 ? minutes + "m" : out.toString();
    }

    private static String resolvePlaceholders(String template, Map<String, String> vars) {
        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String message(PlayerFixture fixture, String fallback, String username, String code) {
        String template = fixture != null && fixture.message() != null ? fixture.message() : fallback;
        String result = template.replace("{player}", username).replace("{username}", username);
        if (code != null) {
            result = result.replace("{code}", code);
        }
        return result;
    }

    /**
     * A stable 6-digit code. The real bot mints a random one; the stub derives it from the UUID so a
     * test can assert on the exact value without first scraping it out of a previous response.
     */
    private static String authCode(PlayerFixture fixture, String uuid) {
        if (fixture != null && fixture.authCode() != null) {
            return fixture.authCode();
        }
        int derived = Math.abs(uuid.toLowerCase(Locale.ROOT).hashCode()) % 900000 + 100000;
        return String.valueOf(derived);
    }

    private static JsonArray toArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static void addNullable(JsonObject object, String key, String value) {
        if (value == null) {
            object.add(key, com.google.gson.JsonNull.INSTANCE);
        } else {
            object.addProperty(key, value);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String optString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        return object.get(key).getAsString();
    }

    private static JsonObject parseObject(String body) {
        if (body == null || body.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (JsonParseException e) {
            return new JsonObject();
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void sendEnvelope(HttpExchange exchange, int status, JsonElement data) throws IOException {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("success", true);
        envelope.add("data", data);
        sendJson(exchange, status, envelope);
    }

    private void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("success", false);
        envelope.add("error", error);
        sendJson(exchange, status, envelope);
    }

    /**
     * The bot's HMAC middleware answers a failed guild-route signature with a bare
     * {@code {"error":"Unauthorized"}} — <strong>not</strong> the success envelope every other
     * response uses. That inconsistency is reproduced deliberately: a client that assumes
     * {@code error.code} exists on every failure breaks against the real bot, and a fixture that
     * tidied it up would hide that.
     */
    private void sendUnauthorized(HttpExchange exchange) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("error", "Unauthorized");
        sendJson(exchange, 401, body);
    }

    private void sendJson(HttpExchange exchange, int status, JsonElement body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
