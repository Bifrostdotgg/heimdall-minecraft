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
