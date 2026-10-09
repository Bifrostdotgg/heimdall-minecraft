package com.heimdall.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.http.ApiClient;
import com.heimdall.core.http.ApiSettings;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.http.model.PluginRelease;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.update.ReleaseSource;
import com.heimdall.core.update.UpdateService;
import com.heimdall.stubbot.StubBot;
import com.heimdall.stubbot.StubBotConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code /hd check} is the headline of the fresh-check feature, and nothing below the command pins
 * it: {@code UpdateService} has both a cached and a fresh check, and the admin adapter choosing the
 * wrong one compiles and passes every other test. This drives the real adapter.
 */
class UpdateWiringFreshTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private ScheduledExecutorService scheduler;
    private StubBot bot;
    private HeimdallExecutors executors;

    @AfterEach
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (executors != null) {
            executors.shutdown(2000);
        }
        if (bot != null) {
            bot.close();
        }
    }

    @Test
    @DisplayName("/hd check reaches the bot as plugin/latest?fresh=1, end to end")
    void checkIsFreshOnTheWire() {
        bot = StubBot.start(StubBotConfig.withDemoFixtures().bindHost("127.0.0.1").port(0));
        executors = new HeimdallExecutors(logger, 2);
        ApiClient client = new ApiClient(logger, ApiSettings.builder()
                .baseUrl(bot.baseUrl())
                .guildId(StubBotConfig.DEFAULT_GUILD_ID)
                .apiKey(StubBotConfig.DEFAULT_API_KEY)
                .serverId("survival")
                .timeoutMs(5000)
                .retries(2)
                .retryDelayMs(25)
                .build(), executors.io());
        scheduler = Executors.newSingleThreadScheduledExecutor();
        UpdateService service = new UpdateService(logger, "3.0.0",
                new UpdateWiring.GatewayReleaseSource(new HeimdallApi(client)), null, null, scheduler);

        // The real admin adapter, the real service, the real gateway source and the real client.
        new UpdateWiring.ServiceAdmin(service).checkNow();
        assertEquals("fresh=1", bot.lastRequestQuery("GET plugin/latest"));

        // And the periodic path's call through the same source stays cached.
        service.checkNow();
        assertEquals("", bot.lastRequestQuery("GET plugin/latest"));
    }

    @Test
    @DisplayName("/hd check asks the bot to skip its release cache")
    void checkIsFresh() {
        final List<Boolean> flags = new ArrayList<Boolean>();
        ReleaseSource source = new ReleaseSource() {
            @Override
            public CompletableFuture<PluginRelease> latestRelease(boolean fresh) {
                flags.add(fresh);
                return CompletableFuture.completedFuture(PluginRelease.builder()
                        .version("3.0.0")
                        .downloadUrl("https://github.com/x/y/releases/download/3.0.0/p.jar")
                        .build());
            }

            @Override
            public long joinTimeoutMs() {
                return 2_000L;
            }
        };
        scheduler = Executors.newSingleThreadScheduledExecutor();
        UpdateService service = new UpdateService(logger, "3.0.0", source, null, null, scheduler);

        new UpdateWiring.ServiceAdmin(service).checkNow();

        assertEquals(Arrays.asList(true), flags);
    }
}
