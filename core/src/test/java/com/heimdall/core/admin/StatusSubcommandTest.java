package com.heimdall.core.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.BuildConstants;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.module.HeimdallModule;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.testing.RecordingCommands;
import com.heimdall.core.update.InstallOutcome;
import com.heimdall.core.util.Registration;
import com.heimdall.core.wiring.HeimdallRuntime;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /hd status}: the one-screen summary and the detail views behind it.
 *
 * <p>The summary's whole point is that it fits, so its length is asserted, not eyeballed. The other
 * thing worth pinning is that shortening it did not merge states an operator confuses: each one
 * below still produces its own row value and its own problem line.
 */
class StatusSubcommandTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    @TempDir
    Path dataDir;

    private FakePlatform platform;
    private HeimdallRuntime runtime;
    private Registration installed = Registration.NONE;
    private FakeCommandSource admin;

    @BeforeEach
    void setUp() {
        platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        BootstrapStore store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
        runtime = HeimdallRuntime.builder(logger, platform).bootstrapStore(store).build();
        admin = FakeCommandSource.player("Adam").grant(AdminCommand.PERMISSION);
    }

    @AfterEach
    void tearDown() {
        installed.close();
        runtime.close();
    }

    private RecordingCommands commands() {
        return platform.commandRegistry();
    }

    private AdminContext.Builder context() {
        return AdminContext.builder(runtime)
                .role(ServerRole.STANDALONE)
                .pluginVersion(BuildConstants.VERSION);
    }

    private void install(AdminContext.Builder context) {
        installed = AdminCommand.install(
                commands(), context.build(), "hd", Collections.singletonList("heimdall"));
    }

    private List<String> say(String... args) {
        admin.clearMessages();
        assertTrue(commands().run(admin, "hd", args), "the handler must claim the invocation");
        return new ArrayList<String>(admin.messageText());
    }

    private static boolean anyContains(List<String> lines, String needle) {
        for (String line : lines) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private void registerModule(final String id, final boolean failsToStart) {
        runtime.modules().register(new HeimdallModule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Set<String> capabilities() {
                return Collections.emptySet();
            }

            @Override
            public Set<ServerRole> roles() {
                return Collections.emptySet();
            }

            @Override
            public void enable(ModuleContext ctx) {
                if (failsToStart) {
                    throw new IllegalStateException("boom");
                }
            }

            @Override
            public void disable() {
            }
        });
    }

    @Nested
    @DisplayName("the summary")
    class Summary {

        @Test
        @DisplayName("is a header, the five rows in a fixed order, and a footer")
        void shape() {
            install(context());

            List<String> lines = say("status");

            assertTrue(lines.get(0).contains("Heimdall " + BuildConstants.VERSION), lines.get(0));
            assertTrue(lines.get(0).contains("standalone"), "the RESOLVED role, not the configured one");
            int bot = indexOf(lines, "Bot ");
            int tunnel = indexOf(lines, "Tunnel ");
            int modules = indexOf(lines, "Modules ");
            int mirror = indexOf(lines, "Mirror ");
            int updates = indexOf(lines, "Updates ");
            assertEquals(Arrays.asList(1, 2, 3, 4, 5), Arrays.asList(bot, tunnel, modules, mirror, updates),
                    "the rows never move, so the eye learns where to look: " + lines);
            assertTrue(lines.get(lines.size() - 1).contains("/hd status bot · modules · server · all"),
                    lines.get(lines.size() - 1));
        }

        @Test
        @DisplayName("fits one chat screen even with more problems than it prints")
        void capsProblems() {
            registerModule("broken", true);
            registerModule("whitelist", false);
            install(context().updates(new AvailableUpdates("3.0.0-rc.99")));
            // Disabling reconciles against the remote config, so the failing start comes after it.
            say("disable", "whitelist");
            runtime.modules().reconcile(Collections.singleton("broken"));

            List<String> lines = say("status");

            // not set up, broken FAILED, locally disabled, update available: four problems.
            assertTrue(lines.size() <= 10, "a status screen that scrolls is one nobody reads: " + lines);
            assertTrue(anyContains(lines, "+2 more"), lines.toString());
            assertTrue(anyContains(lines, "/hd status all"));
        }

        @Test
        @DisplayName("an unconfigured server says so and says how to fix it")
        void unconfigured() {
            install(context());

            List<String> lines = say("status");

            assertTrue(anyContains(lines, "Bot not set up"), lines.toString());
            assertTrue(anyContains(lines, "/hd setup <code>"));
            assertTrue(anyContains(lines, "Tunnel waiting on the bot"),
                    "one cause is one problem line, not one per row it knocks over");
            assertFalse(anyContains(lines, "console tap"),
                    "a healthy console tap is detail, not summary");
        }

        @Test
        @DisplayName("a module that failed to start is counted, named and red")
        void failedModule() {
            registerModule("rolesync", true);
            runtime.modules().reconcile(Collections.singleton("rolesync"));
            install(context());

            StatusSubcommand.Report report = StatusSubcommand.assess(context().build());

            StatusSubcommand.Row modules = row(report, "Modules");
            assertEquals(StatusSubcommand.Health.BROKEN, modules.health);
            assertTrue(modules.value.contains("1 FAILED"), modules.value);
            assertTrue(problemSaying(report, "rolesync failed to start"));
        }

        @Test
        @DisplayName("a locally disabled module raises a line naming the way back")
        void locallyDisabled() {
            registerModule("whitelist", false);
            install(context());
            say("disable", "whitelist");

            List<String> lines = say("status");

            assertTrue(anyContains(lines, "Locally disabled: whitelist"), lines.toString());
            assertTrue(anyContains(lines, "/hd enable <module>"));
        }

        @Test
        @DisplayName("an available update is yellow and says the command")
        void updateAvailable() {
            install(context().updates(new AvailableUpdates("3.0.0-rc.99")));

            StatusSubcommand.Report report = StatusSubcommand.assess(context()
                    .updates(new AvailableUpdates("3.0.0-rc.99")).build());

            assertEquals(StatusSubcommand.Health.ATTENTION, row(report, "Updates").health);
            assertTrue(problemSaying(report, "/hd update"));
        }

        @Test
        @DisplayName("names the proxy's command on a proxy")
        void proxyLabel() {
            installed = AdminCommand.install(commands(), context().label("hdp").build(),
                    "hdp", Collections.singletonList("heimdallproxy"));
            admin.clearMessages();

            assertTrue(commands().run(admin, "hdp", "status"));

            List<String> lines = new ArrayList<String>(admin.messageText());
            assertTrue(anyContains(lines, "/hdp setup <code>"), lines.toString());
            assertTrue(anyContains(lines, "/hdp status bot"));
            assertFalse(anyContains(lines, "/hd "), "a proxy has no /hd: " + lines);
        }
    }

    @Nested
    @DisplayName("the mirror row")
    class Mirror {

        @Test
        @DisplayName("shows the size and the age of the last sync")
        void fresh() {
            StatusSubcommand.Row mirror = row(StatusSubcommand.assess(context()
                    .whitelist(new Whitelist(3072, minutesAgo(4), TimeUnit.MINUTES.toMillis(5)))
                    .build()), "Mirror");

            assertEquals(StatusSubcommand.Health.OK, mirror.health);
            assertEquals("3,072 players, synced 4m ago", mirror.value);
        }

        @Test
        @DisplayName("goes yellow when the poll has clearly stopped landing")
        void stale() {
            StatusSubcommand.Report report = StatusSubcommand.assess(context()
                    .whitelist(new Whitelist(10, minutesAgo(120), TimeUnit.MINUTES.toMillis(5)))
                    .build());

            assertEquals(StatusSubcommand.Health.ATTENTION, row(report, "Mirror").health);
            assertTrue(problemSaying(report, "last synced 2h ago but should every 5m"));
            assertTrue(problemSaying(report, "/hd cache sync"));
        }

        @Test
        @DisplayName("is not called stale for one slow poll on a short interval")
        void floor() {
            StatusSubcommand.Row mirror = row(StatusSubcommand.assess(context()
                    .whitelist(new Whitelist(10, minutesAgo(10), TimeUnit.MINUTES.toMillis(1)))
                    .build()), "Mirror");

            assertEquals(StatusSubcommand.Health.OK, mirror.health);
        }

        @Test
        @DisplayName("a mirror that never synced says so")
        void neverSynced() {
            StatusSubcommand.Report report = StatusSubcommand.assess(context()
                    .whitelist(new Whitelist(0, 0L, TimeUnit.MINUTES.toMillis(5)))
                    .build());

            assertEquals("0 players, never synced", row(report, "Mirror").value);
            assertTrue(problemSaying(report, "has not synced"));
        }

        @Test
        @DisplayName("with the pre-warm off, its age means nothing and is not judged")
        void prewarmOff() {
            StatusSubcommand.Report report = StatusSubcommand.assess(context()
                    .whitelist(new Whitelist(1, 0L, 0L))
                    .build());

            assertEquals(StatusSubcommand.Health.OK, row(report, "Mirror").health);
            assertEquals("1 player, pre-warm off", row(report, "Mirror").value);
            assertFalse(problemSaying(report, "mirror"));
        }

        @Test
        @DisplayName("is grey with no whitelist module running")
        void absent() {
            StatusSubcommand.Row mirror = row(StatusSubcommand.assess(context().build()), "Mirror");

            assertEquals(StatusSubcommand.Health.IDLE, mirror.health);
        }
    }

    @Nested
    @DisplayName("the detail views")
    class Sections {

        @Test
        @DisplayName("bot carries everything the summary folded away")
        void bot() {
            install(context());

            List<String> lines = say("status", "bot");

            for (String label : Arrays.asList("endpoint:", "status:", "guild:", "serverId:",
                    "identity:", "api:", "tunnel:")) {
                assertTrue(anyContains(lines, label), label + " missing from " + lines);
            }
        }

        @Test
        @DisplayName("server carries the versions, the console tap and the updater")
        void server() {
            install(context().core("3.0.0-rc.22 (sha256 96fe2a464fcf)", "3.0.0-rc.20"));

            List<String> lines = say("status", "server");

            assertTrue(anyContains(lines, "sha256 96fe2a464fcf"));
            assertTrue(anyContains(lines, "shell: 3.0.0-rc.20"));
            assertTrue(anyContains(lines, "console tap: ok"));
            assertTrue(anyContains(lines, "updates:"));
        }

        @Test
        @DisplayName("modules is the module list plus the mirror in full")
        void modules() {
            registerModule("bridge", false);
            install(context().whitelist(new Whitelist(41, minutesAgo(1), 60_000L)));

            List<String> lines = say("status", "modules");

            assertTrue(anyContains(lines, "bridge"));
            assertTrue(anyContains(lines, "whitelist mirror: 41 entries"));
        }

        @Test
        @DisplayName("all is every section under one header")
        void all() {
            install(context());

            List<String> lines = say("status", "all");

            assertTrue(anyContains(lines, "full status"));
            assertTrue(anyContains(lines, "Bot connection"));
            assertTrue(anyContains(lines, "whitelist mirror"));
            assertTrue(anyContains(lines, "console tap"));
        }

        @Test
        @DisplayName("an unknown section lists the real ones")
        void unknown() {
            install(context());

            List<String> lines = say("status", "nope");

            assertTrue(anyContains(lines, "bot|modules|server|all"), lines.toString());
        }

        @Test
        @DisplayName("tab completion offers the sections")
        void completes() {
            install(context());

            List<String> suggestions = commands().complete(admin, "hd", "status", "");

            assertTrue(suggestions.containsAll(StatusSubcommand.SECTIONS), suggestions.toString());
        }
    }

    @Nested
    @DisplayName("formatting")
    class Formatting {

        @Test
        @DisplayName("the header keeps the software's name and leading version only")
        void shortServer() {
            assertEquals("Purpur 26.2", StatusSubcommand.shortServer(
                    "Purpur 26.2-2622-48bcd91 (MC: 26.2)", "purpur", "26.2.build.2622-stable"));
            assertEquals("Paper 1.8.8", StatusSubcommand.shortServer(
                    "Paper git-PaperSpigot-445 (MC: 1.8.8)", "paper", "1.8.8"));
            assertEquals("Velocity 3.4.0", StatusSubcommand.shortServer(
                    "Velocity 3.4.0-SNAPSHOT (git-a5680fc2-b613)", "velocity", ""));
            assertEquals("Waterfall", StatusSubcommand.shortServer(
                    "Waterfall git:Waterfall-Bootstrap:1.21-R0.1-SNAPSHOT:abc:1", "waterfall", ""));
            assertEquals("velocity", StatusSubcommand.shortServer("", "velocity", ""));
            assertEquals("unknown server", StatusSubcommand.shortServer("", "", ""));
        }

        @Test
        @DisplayName("ages read in one unit")
        void ages() {
            assertEquals("45s ago", StatusSubcommand.ago(45_000L));
            assertEquals("4m ago", StatusSubcommand.ago(TimeUnit.MINUTES.toMillis(4) + 30_000L));
            assertEquals("3h ago", StatusSubcommand.ago(TimeUnit.HOURS.toMillis(3)));
            assertEquals("2d ago", StatusSubcommand.ago(TimeUnit.DAYS.toMillis(2)));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static StatusSubcommand.Row row(StatusSubcommand.Report report, String label) {
        for (StatusSubcommand.Row row : report.rows) {
            if (row.label.equals(label)) {
                return row;
            }
        }
        throw new AssertionError("no " + label + " row");
    }

    private static boolean problemSaying(StatusSubcommand.Report report, String needle) {
        for (StatusSubcommand.Problem problem : report.problems) {
            if (problem.text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static long minutesAgo(long minutes) {
        return System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(minutes) - 5_000L;
    }

    /** A whitelist module with a mirror of a given size and age. */
    private static final class Whitelist implements WhitelistAdmin {

        private final int entries;
        private final long lastSyncAtMs;
        private final long intervalMs;

        Whitelist(int entries, long lastSyncAtMs, long intervalMs) {
            this.entries = entries;
            this.lastSyncAtMs = lastSyncAtMs;
            this.intervalMs = intervalMs;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String stats() {
            return entries + " entries (0 expired), etag=abc, last synced 3s ago";
        }

        @Override
        public int mirrorEntries() {
            return entries;
        }

        @Override
        public long lastSyncAtMs() {
            return lastSyncAtMs;
        }

        @Override
        public long syncIntervalMs() {
            return intervalMs;
        }

        @Override
        public void clear() {
        }

        @Override
        public int cleanup() {
            return 0;
        }

        @Override
        public void syncNow() {
        }

        @Override
        public LoginProbe probe(String playerName) {
            return LoginProbe.forPlayer(playerName, "").allowed(true).stage("test").build();
        }
    }

    /** An updater whose last check found something newer. */
    private static final class AvailableUpdates implements UpdateAdmin {

        private final String latest;

        AvailableUpdates(String latest) {
            this.latest = latest;
        }

        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public String currentVersion() {
            return BuildConstants.VERSION;
        }

        @Override
        public boolean checkNow() {
            return true;
        }

        @Override
        public InstallOutcome updateNow(Object audience) {
            return InstallOutcome.failed("not in a test");
        }

        @Override
        public boolean isUpdateAvailable() {
            return true;
        }

        @Override
        public String latestVersion() {
            return latest;
        }
    }
}
