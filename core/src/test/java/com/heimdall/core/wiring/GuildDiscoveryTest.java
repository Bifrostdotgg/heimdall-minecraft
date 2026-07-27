package com.heimdall.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.http.ApiClient;
import com.heimdall.core.http.ApiSettings;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.stubbot.StubBot;
import com.heimdall.stubbot.StubBotConfig;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guild discovery against {@code :stub-bot}, over a real socket and a real signature.
 *
 * <p>The point of running it against the fixture rather than a fake client is that the endpoint is
 * the one place the plugin talks to the bot <em>without</em> a guild in the path, so the thing most
 * likely to be wrong is the canonical string being signed. A fake would agree with whatever this
 * repo believes; the stub verifies the signature the way the bot does.
 */
class GuildDiscoveryTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    private StubBot bot;
    private HeimdallExecutors executors;

    @BeforeEach
    void startStub() {
        bot = StubBot.start(StubBotConfig.withDemoFixtures().bindHost("127.0.0.1").port(0));
        executors = new HeimdallExecutors(logger, 2);
    }

    @AfterEach
    void stopStub() {
        if (executors != null) {
            executors.shutdown(2000);
        }
        if (bot != null) {
            bot.close();
        }
    }

    private ApiClient client(String baseUrl, String apiKey) {
        return new ApiClient(logger, ApiSettings.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                // Deliberately empty: this is the state discovery exists for.
                .guildId("")
                .tokenId("token-42")
                .serverId("survival")
                .timeoutMs(2000)
                .retries(1)
                .retryDelayMs(10)
                .build(), executors.io());
    }

    @Test
    @DisplayName("the token resolves to a guild, and discovery stops asking")
    void resolvesAndStops() throws Exception {
        final CountDownLatch resolved = new CountDownLatch(1);
        final AtomicReference<String> guild = new AtomicReference<String>();
        GuildDiscovery discovery = new GuildDiscovery(
                logger, client(bot.baseUrl(), StubBotConfig.DEFAULT_API_KEY), executors.scheduler(),
                new Consumer<String>() {
                    @Override
                    public void accept(String value) {
                        guild.set(value);
                        resolved.countDown();
                    }
                });

        discovery.start();

        assertTrue(resolved.await(20, TimeUnit.SECONDS), "identify never answered");
        assertEquals(StubBotConfig.DEFAULT_GUILD_ID, guild.get());
        assertTrue(discovery.isResolved(), "a resolved discovery must not keep a retry armed");
    }

    @Test
    @DisplayName("a refused token is named as one, not as an unreachable bot")
    void aRefusedTokenIsItsOwnState() throws Exception {
        GuildDiscovery discovery = new GuildDiscovery(
                logger, client(bot.baseUrl(), "not-the-shared-secret"), executors.scheduler(),
                new Consumer<String>() {
                    @Override
                    public void accept(String value) {
                        throw new AssertionError("must not resolve against a refused signature");
                    }
                });

        discovery.start();

        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline
                && !logger.logged(LogLevel.WARN, "refused this server's token")) {
            Thread.sleep(25);
        }

        // A 401 means the bot answered and said no. Reporting that as "cannot reach the bot" sends
        // an operator to look at their network instead of at their token, which is the one thing
        // they can actually fix.
        assertTrue(logger.logged(LogLevel.WARN, "refused this server's token"),
                "the first failure has to be visible, and correctly attributed: " + logger.records());
        assertEquals(GuildDiscovery.Status.TOKEN_REFUSED, discovery.status());
        assertFalse(logger.logged(LogLevel.WARN, "could not resolve this server's guild"),
                "a refused token is not an unreachable bot");
        assertFalse(discovery.isResolved());

        // One warning however many attempts follow, until the re-warn timer elapses. A bot down for
        // an hour must not write the same line every five minutes — but it must not go silent
        // forever either, which is what WARN_INTERVAL_MS is for.
        assertEquals(1, logger.messagesAt(LogLevel.WARN).stream()
                .filter(line -> line.contains("refused this server's token"))
                .count());

        discovery.close();
    }

    @Test
    @DisplayName("an unreachable bot is a different state from a refused token")
    void anUnreachableBotIsNotARefusal() throws Exception {
        GuildDiscovery discovery = new GuildDiscovery(
                logger, client("http://127.0.0.1:1", StubBotConfig.DEFAULT_API_KEY),
                executors.scheduler(),
                new Consumer<String>() {
                    @Override
                    public void accept(String value) {
                        throw new AssertionError("nothing is listening on that port");
                    }
                });

        discovery.start();

        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline
                && discovery.status() == GuildDiscovery.Status.DISCOVERING) {
            Thread.sleep(25);
        }
        assertEquals(GuildDiscovery.Status.UNREACHABLE, discovery.status());
        assertTrue(logger.logged(LogLevel.WARN, "could not resolve this server's guild"),
                logger.records().toString());
        assertFalse(discovery.lastFailure().isEmpty(), "a status line needs something to print");

        discovery.close();
    }

    @Test
    @DisplayName("close stops a retry that was already armed")
    void closeDisarms() throws Exception {
        final AtomicInteger callbacks = new AtomicInteger();
        GuildDiscovery discovery = new GuildDiscovery(
                logger, client("http://127.0.0.1:1", StubBotConfig.DEFAULT_API_KEY),
                executors.scheduler(),
                new Consumer<String>() {
                    @Override
                    public void accept(String value) {
                        callbacks.incrementAndGet();
                    }
                });

        discovery.start();
        Thread.sleep(200);
        discovery.close();
        discovery.close();

        assertTrue(discovery.isResolved(), "closed and resolved are the same state from outside");
        assertEquals(0, callbacks.get());
    }

    @Test
    @DisplayName("everything it needs is required at construction")
    void requiresItsCollaborators() {
        ApiClient api = client(bot.baseUrl(), StubBotConfig.DEFAULT_API_KEY);
        Consumer<String> sink = new Consumer<String>() {
            @Override
            public void accept(String value) {
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> new GuildDiscovery(null, api, executors.scheduler(), sink));
        assertThrows(IllegalArgumentException.class,
                () -> new GuildDiscovery(logger, null, executors.scheduler(), sink));
        assertThrows(IllegalArgumentException.class,
                () -> new GuildDiscovery(logger, api, null, sink));
        assertThrows(IllegalArgumentException.class,
                () -> new GuildDiscovery(logger, api, executors.scheduler(), null));
    }
}
