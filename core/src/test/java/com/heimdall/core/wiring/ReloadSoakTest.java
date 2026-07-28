package com.heimdall.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.Await;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.stubbot.StubBot;
import com.heimdall.stubbot.StubBotConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /hd reload}, twenty times, against a real stub — the test the reload verb shipped without.
 *
 * <h2>Why it exists</h2>
 *
 * <p>An adversarial review worried the tunnel was "rebuilt per boot"; it is not — {@code TunnelClient}
 * is {@code final}, reconfigured in place, and reused across reloads. This is the missing evidence
 * for that: the reload path touches the tunnel, the HTTP client, guild discovery and the module
 * reconcile all at once, and a grep confirmed nothing exercised it. v2's reload leaked a scheduler
 * and a selector thread on every call (departure D-reload / the {@code RuntimeSubcommands.Reload}
 * javadoc), so a reloaded-a-few-times server ran several half-live sockets. The assertion here is the
 * opposite: <strong>the thread population is stable across twenty reloads</strong>, the tunnel stays
 * connected, and shutdown is still clean afterwards.
 */
class ReloadSoakTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    private StubBot bot;

    @TempDir
    Path dataDir;

    @BeforeEach
    void startBot() {
        bot = StubBot.start(StubBotConfig.withDemoFixtures().bindHost("127.0.0.1").port(0));
    }

    @AfterEach
    void stopBot() {
        if (bot != null) {
            bot.close();
        }
    }

    private BootstrapStore store() {
        return new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
    }

    private void writeBootstrap(BootstrapStore store, String serverId) throws Exception {
        store.save(BootstrapConfig.builder()
                .endpoint(bot.baseUrl())
                .tokenId("stub-token")
                .token(StubBotConfig.DEFAULT_API_KEY)
                .serverId(serverId)
                .build());
    }

    private HeimdallRuntime runtime(BootstrapStore store) {
        return HeimdallRuntime.builder(logger, new FakePlatform(ServerRole.STANDALONE, dataDir))
                .bootstrapStore(store)
                .build();
    }

    /** How many live daemon threads carry one of Heimdall's pool names. */
    private static int poolThreadCount() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (!thread.isAlive()) {
                continue;
            }
            String name = thread.getName();
            if (name.startsWith("heimdall-io") || name.startsWith("heimdall-sched")
                    || name.startsWith("heimdall-ws")) {
                count++;
            }
        }
        return count;
    }

    @Test
    @DisplayName("twenty reloads leak no threads, stay connected, and shut down cleanly")
    void twentyReloadsAreStable() throws Exception {
        BootstrapStore store = store();
        writeBootstrap(store, "survival");
        HeimdallRuntime runtime = runtime(store);
        runtime.start();

        assertNotNull(bot.ws().awaitConnection(StubBotConfig.DEFAULT_GUILD_ID, "survival", 20_000L),
                "the tunnel must be up before we start reloading it");
        Await.until("the client to agree it is connected", () -> runtime.tunnel().isConnected());

        // The pools are created once, at construction, and owned by the runtime for its whole life.
        // Whatever their count is now, it must not grow — that is the leak this guards.
        int baseline = poolThreadCount();

        for (int i = 0; i < 20; i++) {
            String out = runtime.reload();
            assertTrue(out.toLowerCase().contains("reconnect")
                            || out.toLowerCase().contains("re-read"),
                    "reload should report what it did, got: " + out);
            // The reload aborts and re-dials in place; give it a moment to come back before the next.
            Await.until("the tunnel to reconnect after reload " + i,
                    () -> runtime.tunnel().isConnected(), 10_000L);
        }

        int after = poolThreadCount();
        assertEquals(baseline, after,
                "twenty reloads leaked " + (after - baseline) + " pool thread(s) — the tunnel is "
                        + "being rebuilt rather than reconfigured in place, which is v2's bug");
        assertTrue(runtime.tunnel().isConnected(), "and it is still connected");

        runtime.close();
        Await.until("the pools to drain on shutdown", () -> poolThreadCount() == 0, 10_000L);
    }

    @Test
    @DisplayName("a reload that changes credentials re-runs guild discovery and re-dials")
    void credentialsChangedReloadRediscovers() throws Exception {
        BootstrapStore store = store();
        writeBootstrap(store, "survival");
        HeimdallRuntime runtime = runtime(store);
        runtime.start();

        assertNotNull(bot.ws().awaitConnection(StubBotConfig.DEFAULT_GUILD_ID, "survival", 20_000L));

        // Change the serverId on disk, which is one of the four fields the reload treats as a
        // credential change — the branch that closes and rebuilds GuildDiscovery and re-dials, and
        // the branch nothing tested.
        writeBootstrap(store, "survival-renamed");

        String out = runtime.reload();
        assertTrue(out.toLowerCase().contains("credentials"),
                "a credential change should say so, got: " + out);

        // It re-dialled on the new server id, which means the whole credentialsChanged path ran:
        // GuildDiscovery was rebuilt, identify was re-asked, and the tunnel reconnected.
        assertNotNull(bot.ws().awaitConnection(StubBotConfig.DEFAULT_GUILD_ID, "survival-renamed", 20_000L),
                "the tunnel must reconnect under the new server id");

        runtime.close();
    }
}
