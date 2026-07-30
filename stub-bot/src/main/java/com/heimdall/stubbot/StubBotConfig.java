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
import java.util.Collections;
import java.util.Set;
import java.util.LinkedHashSet;
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

    /**
     * Server ids that are deliberately NOT in the registry.
     *
     * <p>Empty by default, so every serverId is registered and nothing that existed before this flag
     * behaves differently. Listing one here reproduces the bot's unregistered path: it still
     * connects, still gets an {@code identify_ack}, and gets {@code configVersion: 0} and no
     * {@code config.push} at all.
     *
     * <p>Modelled as a deny-list rather than an allow-list precisely so the default is "registered".
     * An allow-list would make an empty registry mean "nothing is registered", and every existing
     * test would silently start exercising the unregistered path instead of the one it was written
     * for.
     */
    private final Set<String> unregisteredServers = new LinkedHashSet<>();

    /** Server ids whose registry row names a different token. Connecting as one is a 403. */
    private final Set<String> foreignServers = new LinkedHashSet<>();

    /**
     * Setup codes to mint at startup, as {@code CODE:serverName:serverId} triples.
     *
     * <p>Exists for one caller that cannot use {@code bot.http().issueClaimCode(...)}: the smoke
     * matrix, which starts this stub in a container and drives a real server against it, and has no
     * way to reach into the JVM. Without it the setup scenario cannot be exercised at all from the
     * outside, which would leave the one flow phase 1e is about proven only by unit tests.
     *
     * <p>The {@code serverId} is optional and is the reason the triple is not a pair. A claim
     * normally invents a random UUID, which is fine in a test that reads the response and useless in
     * a shell script that has to assert on a log line naming the server. Given one, the claim uses
     * it verbatim.
     */
    private final Map<String, String[]> claimCodes = new LinkedHashMap<>();

    /** When true the registry cannot be read, which is what turns an incumbent clash into a 503. */
    private boolean registryUnreadable;

    /**
     * Request types to fire at a server the moment it acknowledges its config — once per server.
     *
     * <p>Empty by default, so nothing that existed before this flag behaves differently.
     *
     * <p>It exists for the same caller as {@link #claimCodes}: the smoke matrix drives a real server
     * in a container and has no way to reach into this JVM and call {@code bot.ws().getPlayers(...)}.
     * Without it the on-demand half of the contract — the dashboard asking a live server a question
     * and getting an answer — is provable only by unit tests, which is precisely the gap that let v3
     * ship with the plumbing for {@code get_players} and nothing subscribed to it.
     *
     * <p>Fired on {@code config.ack} rather than on {@code identify}, so that a request a module has
     * to answer is asked after the modules are up. Firing it once per server is deliberate: a config
     * push during a hot-toggle test produces a second ack, and re-asking on every one of those would
     * turn a debugging aid into noise.
     */
    private final List<String> requestOnAck = new ArrayList<>();

    /**
     * Rendered Discord lines to push at a server the moment it acknowledges its config — once per
     * server.
     *
     * <p>Empty by default, so nothing that existed before this flag behaves differently. It exists
     * for the same caller as {@link #requestOnAck}: the connected smoke drives a real server in a
     * container and cannot call {@code bot.ws().sendBridgeDiscord(...)} from a shell script.
     *
     * <p>Each entry is a <strong>finished</strong> legacy-§ string, because that is what the real
     * bot sends — the template is resolved bot-side and the user's content inserted after
     * formatting. Entries are separated by {@code |} rather than by a comma, which is the one place
     * this differs from {@code requestOnAck}: a request type never contains a comma and a chat line
     * very often does.
     */
    private final List<String> discordOnAck = new ArrayList<>();

    private long pingIntervalMs = 30_000L;
    private long livenessTimeoutMs = 90_000L;

    /** Highest {@code protocolVersion} the stub will accept in an {@code identify}. */
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
        // The SECOND pending branch: auto-whitelist on a schedule, so the response carries no
        // queuePosition at all. In the demo set because a plugin that reads that key
        // unconditionally breaks here and nowhere else.
        config.players.add(PlayerFixture.of("77777777-7777-7777-7777-777777777777", "ScheduledSam",
                        Outcome.PENDING_APPROVAL)
                .withSchedule("on Friday at 18:00 UTC"));
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
                case "config_version" -> config.configVersion = Integer.parseInt(value.trim());
                case "modules" -> config.modules = JsonParser.parseString(value).getAsJsonObject();
                case "offense_types" -> config.offenseTypes = JsonParser.parseString(value).getAsJsonArray();
                case "plugin_latest" -> config.pluginLatest = JsonParser.parseString(value).getAsJsonObject();
                case "verbose" -> StubLog.setVerbose(Boolean.parseBoolean(value.trim()));
                case "registry_unreadable" -> config.registryUnreadable = Boolean.parseBoolean(value.trim());
                case "request_on_ack" -> config.requestOnAck(List.of(value.split(",")));
                // Split on `|`, not on a comma — see the field. `split` takes a regex, so the pipe
                // is escaped; unescaped it is alternation between two empty branches and every
                // character comes back as its own entry.
                case "discord_on_ack" -> config.discordOnAck(List.of(value.split("\\|")));
                case "foreign_servers" -> {
                    config.foreignServers.clear();
                    for (String id : value.split(",")) {
                        if (!id.trim().isEmpty()) {
                            config.foreignServers.add(id.trim());
                        }
                    }
                }
                case "claim_codes" -> {
                    config.claimCodes.clear();
                    for (String entryText : value.split(",")) {
                        String[] parts = entryText.trim().split(":");
                        if (parts.length == 0 || parts[0].trim().isEmpty()) {
                            continue;
                        }
                        String code = parts[0].trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
                        String serverName = parts.length > 1 ? parts[1].trim() : "Smoke";
                        String serverId = parts.length > 2 ? parts[2].trim() : "";
                        config.claimCodes.put(code, new String[] {serverName, serverId});
                    }
                }
                case "unregistered_servers" -> {
                    config.unregisteredServers.clear();
                    for (String id : value.split(",")) {
                        if (!id.trim().isEmpty()) {
                            config.unregisteredServers.add(id.trim());
                        }
                    }
                }
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
        // Enabled by default, and inert by default at the same time: the bot's own
        // defaultModuleConfig special-cases the bridge to enabled-but-unmapped, because zero
        // channel mappings means zero relay. Nothing here sets relayChat, so the plugin uses its
        // role default — on for a backend, off for a proxy.
        modules.add("bridge", moduleConfig(true, null));
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


    public int configVersion() {
        return configVersion;
    }

    /**
     * Whether a serverId is in the registry.
     *
     * <p>True unless it was explicitly listed as unregistered — see the field for why the default
     * points that way.
     */
    public boolean isRegistered(String serverId) {
        return serverId != null && !unregisteredServers.contains(serverId);
    }

    /** The serverIds being treated as unregistered. */
    /** The codes to mint at startup, as {@code CODE -> [serverName, serverId]}. */
    public Map<String, String[]> claimCodes() {
        return Collections.unmodifiableMap(claimCodes);
    }

    /** Pre-issues a setup code, for a caller that cannot reach {@code StubHttpApi}. */
    public StubBotConfig claimCode(String code, String serverName, String serverId) {
        claimCodes.put(
                code.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""),
                new String[] {serverName, serverId == null ? "" : serverId});
        return this;
    }

    public Set<String> unregisteredServers() {
        return Collections.unmodifiableSet(unregisteredServers);
    }

    /** Marks a serverId as absent from the registry. Chainable, for tests. */
    public StubBotConfig unregisterServer(String serverId) {
        if (serverId != null && !serverId.trim().isEmpty()) {
            unregisteredServers.add(serverId.trim());
        }
        return this;
    }

    /**
     * Server ids that are registered to a DIFFERENT token than this stub's.
     *
     * <p>Connecting as one of these is the 403 case: the registry has a row and it does not point at
     * the presented token. Permanent, and a plugin that keeps retrying will keep getting it.
     */
    public StubBotConfig registerServerToAnotherToken(String serverId) {
        if (serverId != null && !serverId.trim().isEmpty()) {
            foreignServers.add(serverId.trim());
        }
        return this;
    }

    /** Whether a serverId is registered to somebody else's token. */
    public boolean isForeign(String serverId) {
        return serverId != null && foreignServers.contains(serverId);
    }

    /**
     * Makes the registry unreadable, which is the 503 case when an incumbent connection is held by
     * a different token. Transient by nature — a plugin should back off and retry.
     */
    public StubBotConfig registryUnreadable(boolean unreadable) {
        this.registryUnreadable = unreadable;
        return this;
    }

    public boolean registryUnreadable() {
        return registryUnreadable;
    }

    /** Request types to fire once at each server that acknowledges its config. See the field. */
    public List<String> requestOnAck() {
        return Collections.unmodifiableList(requestOnAck);
    }

    /** Sets the on-ack request list. Blank entries are dropped. */
    public StubBotConfig requestOnAck(List<String> types) {
        requestOnAck.clear();
        if (types != null) {
            for (String type : types) {
                if (type != null && !type.trim().isEmpty()) {
                    requestOnAck.add(type.trim());
                }
            }
        }
        return this;
    }

    /** Rendered Discord lines to push once per server on {@code config.ack}. See the field. */
    public List<String> discordOnAck() {
        return Collections.unmodifiableList(discordOnAck);
    }

    /**
     * Sets the on-ack {@code bridge.discord} lines. Blank entries are dropped.
     *
     * <p>Deliberately NOT trimmed, unlike {@link #requestOnAck}: a request type is an identifier and
     * surrounding whitespace is a typo, whereas one of these is a rendered chat line where leading
     * space may be exactly what the template produced.
     */
    public StubBotConfig discordOnAck(List<String> texts) {
        discordOnAck.clear();
        if (texts != null) {
            for (String text : texts) {
                if (text != null && !text.trim().isEmpty()) {
                    discordOnAck.add(text);
                }
            }
        }
        return this;
    }

    /** Puts one serverId back in the registry — what a successful claim does. */
    public StubBotConfig registerServer(String serverId) {
        if (serverId != null) {
            unregisteredServers.remove(serverId.trim());
        }
        return this;
    }

    /** Puts every serverId back in the registry. */
    public StubBotConfig registerAllServers() {
        unregisteredServers.clear();
        return this;
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
