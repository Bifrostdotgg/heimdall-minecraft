package com.heimdall.module.offenses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.http.ApiClient;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.http.ApiSettings;
import com.heimdall.core.http.model.OffenseType;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.stubbot.StubBot;
import com.heimdall.stubbot.StubBotConfig;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The cache: what it fetches, what it offers, and what it does when the fetch fails.
 *
 * <p>The fetch tests run the real {@link ApiClient} against {@code :stub-bot} over a socket, because
 * {@code data} being an <em>array</em> for this endpoint (rather than the object every other one
 * sends) is exactly the sort of thing a hand-written fixture agrees with itself about.
 *
 * <p>The filtering tests go through {@link OffenseTypeCache#replaceAll} instead. The rules under
 * test — enabled-only, sorted, de-duplicated, case-insensitive — need types the stub's demo fixture
 * does not carry, and reaching them by editing the fixture's JSON would put Gson on this module's
 * test classpath to assert something that has nothing to do with the wire.
 */
class OffenseTypeCacheTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    private StubBot bot;
    private HeimdallExecutors executors;
    private ApiClient client;

    @BeforeEach
    void startStub() {
        bot = StubBot.start(StubBotConfig.withDemoFixtures().bindHost("127.0.0.1").port(0));
        executors = new HeimdallExecutors(logger, 2);
        client = new ApiClient(logger, settings(StubBotConfig.DEFAULT_GUILD_ID), executors.io());
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

    private ApiSettings settings(String guildId) {
        return ApiSettings.builder()
                .baseUrl(bot.baseUrl())
                .guildId(guildId)
                .apiKey(StubBotConfig.DEFAULT_API_KEY)
                .serverId("survival")
                .timeoutMs(5000)
                .retries(1)
                .retryDelayMs(25)
                .build();
    }

    private OffenseTypeCache cache(ApiClient api) {
        return new OffenseTypeCache(logger, new HeimdallApi(api));
    }

    /**
     * The gateway a server that has never been set up holds.
     *
     * <p>Not {@code null} any more, and the difference is the point of departure D56: an
     * unconfigured server has a real gateway over a client with no settings, which answers
     * {@code NOT_CONFIGURED} to everything and becomes usable when {@code /hd setup} reconfigures
     * the client underneath it. A test that passed {@code null} would be testing a state production
     * no longer has.
     */
    private OffenseTypeCache unconfiguredCache() {
        return cache(new ApiClient(logger, ApiSettings.builder().build(), Runnable::run));
    }

    private static void await(OffenseTypeCache cache) throws Exception {
        // The future is documented never to complete exceptionally, so a plain get is the whole
        // assertion of that contract: a failed refresh that propagated would fail the test here.
        cache.refresh().get(20, TimeUnit.SECONDS);
    }

    // ── Fetching ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("against the stub bot")
    class Fetching {

        @Test
        @DisplayName("a refresh loads the types the bot serves")
        void refreshPopulates() throws Exception {
            OffenseTypeCache cache = cache(client);
            assertFalse(cache.hasCachedData());

            await(cache);

            assertTrue(cache.hasCachedData());
            assertEquals(1, cache.types().size());
            assertEquals("cheating", cache.types().get(0).typeId());
            assertEquals(Arrays.asList("exploiting", "xray"), cache.matchingSlugs(""),
                    "the slugs come back sorted, not in the order the bot listed them");
            assertTrue(cache.lastRefreshMillis() > 0);
        }

        @Test
        @DisplayName("a failed refresh keeps the previous cache instead of emptying it")
        void aFailedRefreshKeepsWhatItHad() throws Exception {
            OffenseTypeCache cache = cache(client);
            await(cache);
            long firstRefresh = cache.lastRefreshMillis();

            // A guild the bot does not serve: a typed 404, which D3 says is not retried.
            client.reconfigure(settings("999999999999999999"));

            await(cache);

            assertEquals(1, cache.types().size(),
                    "clearing on failure turns one dropped request into 'no offense exists', and the "
                            + "operator's next move is to type the slug and be told it is unknown");
            assertEquals(Arrays.asList("exploiting", "xray"), cache.matchingSlugs(""));
            assertEquals(firstRefresh, cache.lastRefreshMillis(),
                    "a failed attempt is not a refresh");
            assertTrue(logger.logged(LogLevel.WARN, "Failed to refresh offense types"),
                    "silently keeping stale data is how a fleet-wide outage goes unnoticed");
        }

        @Test
        @DisplayName("a server that was never set up cannot be asked, and that is not an error")
        void anUnconfiguredGatewayIsSurvivable() throws Exception {
            OffenseTypeCache cache = unconfiguredCache();

            await(cache);

            assertTrue(cache.types().isEmpty());
            assertTrue(cache.matchingSlugs("").isEmpty());
            assertEquals(0, cache.lastRefreshMillis());
            assertTrue(logger.at(LogLevel.WARN).isEmpty(),
                    "'not set up yet' is the ordinary state of a fresh install, not a warning");
        }
    }

    // ── What it offers ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("matchingSlugs")
    class Filtering {

        private final OffenseTypeCache cache = unconfiguredCache();

        @Test
        @DisplayName("only enabled types are offered")
        void disabledTypesAreNotOffered() {
            cache.replaceAll(Arrays.asList(
                    type("cheating", true, "xray", "exploiting"),
                    type("chat", false, "spam", "slurs")));

            assertEquals(Arrays.asList("exploiting", "xray"), cache.matchingSlugs(""),
                    "offering a slug the bot answers 404 UNKNOWN_OFFENSE for is worse than offering "
                            + "nothing: the operator finds out after the punishment did not land");
            assertEquals(Collections.emptyList(), cache.matchingSlugs("s"));
            assertEquals(2, cache.types().size(), "but /hd offense types still lists both");
        }

        @Test
        @DisplayName("the prefix filter is case-insensitive on both sides")
        void prefixIsCaseInsensitive() {
            cache.replaceAll(Collections.singletonList(type("cheating", true, "XRay", "exploiting")));

            assertEquals(Arrays.asList("xray"), cache.matchingSlugs("XR"));
            assertEquals(Arrays.asList("xray"), cache.matchingSlugs("xr"),
                    "v2 lower-cased only the prefix and relied on the bot lower-casing the slug");
        }

        @Test
        @DisplayName("results are sorted and de-duplicated across types")
        void sortedAndDeduplicated() {
            cache.replaceAll(Arrays.asList(
                    type("cheating", true, "xray", "exploiting"),
                    type("severe", true, "exploiting", "griefing")));

            assertEquals(Arrays.asList("exploiting", "griefing", "xray"), cache.matchingSlugs(""));
        }

        @Test
        @DisplayName("an empty prefix means everything, and an unmatched one means nothing")
        void prefixBoundaries() {
            cache.replaceAll(Collections.singletonList(type("cheating", true, "xray", "exploiting")));

            assertEquals(2, cache.matchingSlugs(null).size());
            assertEquals(2, cache.matchingSlugs("").size());
            assertEquals(Arrays.asList("exploiting"), cache.matchingSlugs("ex"));
            assertEquals(Collections.emptyList(), cache.matchingSlugs("zz"));
        }

        private OffenseType type(String id, boolean enabled, String... slugs) {
            return OffenseType.builder()
                    .typeId(id)
                    .displayName(id)
                    .offenses(Arrays.asList(slugs))
                    .enabled(enabled)
                    .build();
        }
    }

    @Test
    @DisplayName("the snapshot a caller holds is not the one the next refresh replaces")
    void typesAreAnImmutableSnapshot() throws Exception {
        OffenseTypeCache cache = cache(client);
        await(cache);
        List<OffenseType> snapshot = cache.types();

        cache.replaceAll(Collections.<OffenseType>emptyList());

        assertEquals(1, snapshot.size(),
                "v2 cleared and refilled one list in place, so a tab-completing operator could read "
                        + "it mid-swap");
        assertTrue(cache.types().isEmpty());
    }
}
