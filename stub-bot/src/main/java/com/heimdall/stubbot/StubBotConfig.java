package com.heimdall.stubbot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything the stub needs to run, assembled from defaults, environment variables and CLI
 * arguments (in that precedence order — later wins).
 *
 * <p>Every knob has a working default, so {@code ./gradlew :stub-bot:run} with no configuration at
 * all starts a usable fixture. That is intentional: the fastest way to learn the contract should be
 * to start the thing and point curl at it.
 */
public final class StubBotConfig {

    /** A guild id has to be 17-20 digits or the bot's WebSocket route regex will not match it. */
    public static final String DEFAULT_GUILD_ID = "123456789012345678";

    public static final String DEFAULT_API_KEY = "stub-bot-dev-key";

    private static final Gson GSON = new Gson();

    private String bindHost = "0.0.0.0";
    private int port = 8080;
    private String guildId = DEFAULT_GUILD_ID;
    private String apiKey = DEFAULT_API_KEY;
    private Outcome defaultOutcome = Outcome.DENY;
    private final List<PlayerFixture> players = new ArrayList<>();

    private long pingIntervalMs = 30_000L;
    private long livenessTimeoutMs = 90_000L;

    /** Highest {@code protocolVersion} the stub will accept in an {@code identify}. */
    private int maxProtocolVersion = 1;
    private int configVersion = 1;
    private JsonObject modules = defaultModules();
    private JsonArray offenseTypes = defaultOffenseTypes();
    private JsonObject pluginLatest = defaultPluginLatest();

    /** Default fixtures — one player per outcome, so the contract is demonstrated out of the box. */
    public static StubBotConfig withDemoFixtures() {
        StubBotConfig config = new StubBotConfig();
        config.players.add(PlayerFixture.of("11111111-1111-1111-1111-111111111111", "AllowedSteve", Outcome.ALLOW)
                .withGroups(List.of("vip"), List.of("vip", "member")));
        config.players.add(PlayerFixture.of("22222222-2222-2222-2222-222222222222", "DeniedAlex", Outcome.DENY));
        config.players.add(PlayerFixture.of("33333333-3333-3333-3333-333333333333", "PendingCode", Outcome.PENDING_AUTH)
                .withAuthCode("135790"));
        config.players.add(PlayerFixture.of("44444444-4444-4444-4444-444444444444", "RevokedRita", Outcome.REVOKED)
                .withRevocationReason(" for griefing"));
        config.players.add(PlayerFixture.of("55555555-5555-5555-5555-555555555555", "QueuedQuinn", Outcome.PENDING_APPROVAL)
                .withQueuePosition(3));
        config.players.add(PlayerFixture.of("66666666-6666-6666-6666-666666666666", "LegacyLee", Outcome.EXISTING_LINK)
                .withAuthCode("246800"));
        return config;
    }

    /** Reads {@code STUB_BOT_*} environment variables over the demo defaults, then applies CLI args. */
    public static StubBotConfig fromEnvironment(Map<String, String> env, String[] args) {
        StubBotConfig config = withDemoFixtures();
        Map<String, String> settings = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : env.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("STUB_BOT_")) {
                settings.put(key.substring("STUB_BOT_".length()).toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument '" + arg + "' (expected --key=value)");
            }
            int equals = arg.indexOf('=');
            if (equals < 0) {
                throw new IllegalArgumentException("argument '" + arg + "' has no value (expected --key=value)");
            }
            settings.put(arg.substring(2, equals).toLowerCase(Locale.ROOT).replace('-', '_'), arg.substring(equals + 1));
        }

        boolean playersOverridden = false;
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            switch (entry.getKey()) {
                case "bind" -> config.bindHost = value.trim();
                case "port" -> config.port = Integer.parseInt(value.trim());
                case "guild_id" -> config.guildId = value.trim();
                case "api_key" -> config.apiKey = value;
                case "default_outcome" -> config.defaultOutcome = Outcome.parse(value);
                case "ping_interval_ms" -> config.pingIntervalMs = Long.parseLong(value.trim());
                case "liveness_timeout_ms" -> config.livenessTimeoutMs = Long.parseLong(value.trim());
                case "max_protocol_version" -> config.maxProtocolVersion = Integer.parseInt(value.trim());
                case "config_version" -> config.configVersion = Integer.parseInt(value.trim());
                case "modules" -> config.modules = JsonParser.parseString(value).getAsJsonObject();
                case "offense_types" -> config.offenseTypes = JsonParser.parseString(value).getAsJsonArray();
                case "plugin_latest" -> config.pluginLatest = JsonParser.parseString(value).getAsJsonObject();
                case "verbose" -> StubLog.setVerbose(Boolean.parseBoolean(value.trim()));
                case "players" -> {
                    config.setPlayers(parsePlayers(value));
                    playersOverridden = true;
                }
                case "players_file" -> {
                    config.setPlayers(parsePlayers(readFile(value.trim())));
                    playersOverridden = true;
                }
                default -> StubLog.warn("ignoring unknown setting STUB_BOT_" + entry.getKey().toUpperCase(Locale.ROOT));
            }
        }

        if (!playersOverridden) {
            StubLog.info("no STUB_BOT_PLAYERS given — serving the built-in demo fixtures");
        }
        return config;
    }

    private static String readFile(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read player fixtures from " + path, e);
        }
    }

    private static List<PlayerFixture> parsePlayers(String json) {
        PlayerFixture[] parsed = GSON.fromJson(json, PlayerFixture[].class);
        List<PlayerFixture> fixtures = new ArrayList<>();
        if (parsed != null) {
            for (PlayerFixture fixture : parsed) {
                fixture.validate();
                fixtures.add(fixture);
            }
        }
        return fixtures;
    }

    private static JsonObject defaultModules() {
        JsonObject modules = new JsonObject();
        modules.add("whitelist", moduleConfig(true, null));
        JsonObject rolesync = moduleConfig(true, null);
        rolesync.addProperty("mode", "websocket");
        modules.add("rolesync", rolesync);
        modules.add("offenses", moduleConfig(true, null));
        modules.add("console", moduleConfig(false, "not enabled by default — it streams every log line"));
        return modules;
    }

    private static JsonObject moduleConfig(boolean enabled, String note) {
        JsonObject module = new JsonObject();
        module.addProperty("enabled", enabled);
        if (note != null) {
            module.addProperty("note", note);
        }
        return module;
    }

    private static JsonArray defaultOffenseTypes() {
        JsonObject tierWarn = new JsonObject();
        tierWarn.addProperty("points", 1);
        tierWarn.addProperty("action", "warn");
        tierWarn.addProperty("reason", "{offense} — warning ({points} point)");
        tierWarn.addProperty("command", "warn {player} {reason}");

        JsonObject tierTempban = new JsonObject();
        tierTempban.addProperty("points", 3);
        tierTempban.addProperty("action", "tempban");
        tierTempban.addProperty("duration", 1440);
        tierTempban.addProperty("reason", "{offense} — tier {tier} ({points} points)");
        tierTempban.addProperty("command", "tempban {player} {duration} {reason}");

        JsonObject tierPermban = new JsonObject();
        tierPermban.addProperty("points", 5);
        tierPermban.addProperty("action", "permban");
        tierPermban.addProperty("reason", "{offense} — permanent ({points} points)");
        tierPermban.addProperty("command", "ban {player} {reason}");

        JsonArray tiers = new JsonArray();
        tiers.add(tierWarn);
        tiers.add(tierTempban);
        tiers.add(tierPermban);

        JsonArray offenses = new JsonArray();
        offenses.add("xray");
        offenses.add("exploiting");

        JsonObject cheating = new JsonObject();
        cheating.addProperty("_id", "000000000000000000000001");
        cheating.addProperty("guildId", DEFAULT_GUILD_ID);
        cheating.addProperty("typeId", "cheating");
        cheating.addProperty("displayName", "Cheating");
        cheating.addProperty("description", "Client modifications and exploits");
        cheating.addProperty("color", "#ff5555");
        cheating.add("escalationTiers", tiers);
        cheating.add("offenses", offenses);
        cheating.addProperty("enabled", true);

        JsonArray types = new JsonArray();
        types.add(cheating);
        return types;
    }

    private static JsonObject defaultPluginLatest() {
        JsonObject release = new JsonObject();
        release.addProperty("version", "v3.0.0");
        release.addProperty("downloadUrl",
                "https://github.com/Bifrostdotgg/heimdall-minecraft/releases/download/v3.0.0/heimdall-whitelist-3.0.0.jar");
        release.addProperty("releaseNotes", "Stub release served by the stub-bot fixture.");
        release.addProperty("htmlUrl", "https://github.com/Bifrostdotgg/heimdall-minecraft/releases/tag/v3.0.0");
        release.addProperty("publishedAt", "2026-01-01T00:00:00.000Z");
        return release;
    }

    public StubBotConfig setPlayers(List<PlayerFixture> fixtures) {
        players.clear();
        players.addAll(fixtures);
        return this;
    }

    public StubBotConfig addPlayer(PlayerFixture fixture) {
        players.add(fixture);
        return this;
    }

    public StubBotConfig clearPlayers() {
        players.clear();
        return this;
    }

    public StubBotConfig bindHost(String value) {
        this.bindHost = value;
        return this;
    }

    public StubBotConfig port(int value) {
        this.port = value;
        return this;
    }

    public StubBotConfig guildId(String value) {
        this.guildId = value;
        return this;
    }

    public StubBotConfig apiKey(String value) {
        this.apiKey = value;
        return this;
    }

    public StubBotConfig defaultOutcome(Outcome value) {
        this.defaultOutcome = value;
        return this;
    }

    public StubBotConfig pingIntervalMs(long value) {
        this.pingIntervalMs = value;
        return this;
    }

    public StubBotConfig livenessTimeoutMs(long value) {
        this.livenessTimeoutMs = value;
        return this;
    }

    public StubBotConfig maxProtocolVersion(int value) {
        this.maxProtocolVersion = value;
        return this;
    }

    public StubBotConfig configVersion(int value) {
        this.configVersion = value;
        return this;
    }

    public StubBotConfig modules(JsonObject value) {
        this.modules = value;
        return this;
    }

    public String bindHost() {
        return bindHost;
    }

    public int port() {
        return port;
    }

    public String guildId() {
        return guildId;
    }

    public String apiKey() {
        return apiKey;
    }

    public Outcome defaultOutcome() {
        return defaultOutcome;
    }

    public List<PlayerFixture> players() {
        return List.copyOf(players);
    }

    public long pingIntervalMs() {
        return pingIntervalMs;
    }

    public long livenessTimeoutMs() {
        return livenessTimeoutMs;
    }

    public int maxProtocolVersion() {
        return maxProtocolVersion;
    }

    public int configVersion() {
        return configVersion;
    }

    public JsonObject modules() {
        return modules;
    }

    public JsonArray offenseTypes() {
        return offenseTypes;
    }

    public JsonObject pluginLatest() {
        return pluginLatest;
    }
}
