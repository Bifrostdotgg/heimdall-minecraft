package com.heimdall.stubbot;

/**
 * A fake Heimdall bot: the HTTP API and the WebSocket tunnel the Minecraft plugin talks to, on one
 * port, behind real HMAC verification.
 *
 * <p>It exists to be two things at once. To the Docker boot-smoke matrix it is a dependency the
 * plugin can point at without a Discord bot, a Mongo, or a network. To a reader it is the contract
 * itself, executable — the message table in {@code stub-bot/README.md} is derived from this code,
 * and every claim in it is exercised by a test.
 *
 * <p>Usage from a test:
 *
 * <pre>{@code
 * try (StubBot bot = StubBot.start(StubBotConfig.withDemoFixtures().port(0))) {
 *     String base = "http://127.0.0.1:" + bot.port();
 *     ...
 * }
 * }</pre>
 */
public final class StubBot implements AutoCloseable {

    private final StubBotConfig config;
    private final FixtureStore fixtures;
    private final StubHttpApi http;
    private final StubWsServer ws;
    private final PortMultiplexer multiplexer;

    private StubBot(StubBotConfig config) {
        this.config = config;
        this.fixtures = new FixtureStore(config.defaultOutcome());
        this.fixtures.putAll(config.players());

        this.http = new StubHttpApi(config, fixtures);
        this.ws = new StubWsServer(config);
        this.http.start();
        this.ws.start();

        // Both back-ends bind loopback with an ephemeral port; the multiplexer owns the public one.
        int wsPort = ws.awaitPort(10_000L);
        this.multiplexer = new PortMultiplexer(config.bindHost(), config.port(), http.port(), wsPort);
        this.multiplexer.setUpgradeGate(this::upgradeRejection);
        this.multiplexer.start();
        this.ws.startSweep();
    }

    /** Starts the fixture. Pass {@code port(0)} for an ephemeral port, then read {@link #port()}. */
    public static StubBot start(StubBotConfig config) {
        StubBot bot = new StubBot(config);
        StubLog.info("listening on " + config.bindHost() + ":" + bot.port()
                + " (guild " + config.guildId() + ", " + config.players().size() + " player fixtures)");
        return bot;
    }

    /** The public port serving both the HTTP API and the WebSocket upgrade. */
    public int port() {
        return multiplexer.port();
    }

    /** The base URL a plugin should be configured with. */
    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    /** The mutable player/whitelist fixtures, safe to change while running. */
    public FixtureStore fixtures() {
        return fixtures;
    }

    /** The WebSocket side, including the test hooks that push to a connected server. */
    public StubWsServer ws() {
        return ws;
    }

    public StubBotConfig config() {
        return config;
    }

    /** The HTTP side, including the setup-code and config-import test hooks. */
    public StubHttpApi http() {
        return http;
    }

    /**
     * The registry checks the bot runs before it hands a socket to the WebSocket library.
     *
     * <p>Two rejections, and the difference between them is what a plugin needs in order to know
     * whether to retry:
     *
     * <ul>
     *   <li><strong>403</strong> — the serverId has a registry row and it names a different token.
     *       Permanent. Retrying will get the same answer until somebody fixes the configuration, and
     *       a plugin that reconnect-loops on it is a plugin hammering an endpoint that will never
     *       say yes.
     *   <li><strong>503</strong> — the registry could not be read AND a live connection for that id
     *       is held by a different token. Transient: the bot is refusing to guess during an outage
     *       rather than letting two servers share an id. Back off and retry.
     * </ul>
     *
     * <p>A same-token reconnect always passes, by both routes: with a readable registry the row
     * matches, and with an unreadable one the incumbent check does not fire. That is the case that
     * actually happens in production — a server restarting — and it must never be refused.
     */
    private int upgradeRejection(String requestHead) {
        String serverId = serverIdFrom(requestHead);
        if (serverId == null) {
            return 0;
        }
        if (config.isForeign(serverId)) {
            return 403;
        }
        if (config.registryUnreadable() && ws.hasConnection(config.guildId(), serverId)) {
            return 503;
        }
        return 0;
    }

    /** The {@code serverId} query parameter, or {@code "default"} like the bot's, or null. */
    private static String serverIdFrom(String requestHead) {
        int query = requestHead.indexOf("serverId=");
        if (query < 0) {
            return requestHead.contains("/ws/minecraft/") ? "default" : null;
        }
        int start = query + "serverId=".length();
        int end = start;
        while (end < requestHead.length()) {
            char c = requestHead.charAt(end);
            // & space CR LF, written as codepoints: this string came off a raw socket and the
            // delimiters are more legible as their values than as escaped literals.
            if (c == 0x26 || c == 0x20 || c == 0x0D || c == 0x0A) {
                break;
            }
            end++;
        }
        return requestHead.substring(start, end);
    }

    /** Clears the {@code /offend} escalation counters. */
    public void resetInfractions() {
        http.resetInfractions();
    }

    @Override
    public void close() {
        multiplexer.close();
        ws.shutdown();
        http.stop();
        StubLog.info("stopped");
    }
}
