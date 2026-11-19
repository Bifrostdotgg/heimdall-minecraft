package com.heimdall.core.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.BuildConstants;
import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.module.HeimdallModule;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.module.ModuleState;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.testing.RecordingCommands;
import com.heimdall.core.testing.TestText;
import com.heimdall.core.tunnel.ProtocolMode;
import com.heimdall.core.update.InstallOutcome;
import com.heimdall.core.util.Registration;
import com.heimdall.core.wiring.GuildDiscovery;
import com.heimdall.core.wiring.HeimdallRuntime;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
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
    @DisplayName("the summary, on a live runtime")
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
            assertTrue(lines.get(lines.size() - 1).contains("/hd status bot \u00B7 modules \u00B7 server \u00B7 all"),
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

            List<String> all = say("status", "all");
            for (String needle : Arrays.asList("Not set up", "broken failed to start",
                    "Locally disabled: whitelist", "3.0.0-rc.99 is out")) {
                assertTrue(anyContains(all, needle), "+N more has to point at every problem: " + needle);
            }
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

            StatusSubcommand.Report report = StatusSubcommand.assess(
                    StatusSubcommand.Snapshot.of(context().build()));

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

            assertTrue(anyContains(lines, "Locally disabled: whitelist (/hd enable <module>)"),
                    lines.toString());
            assertTrue(anyContains(say("status", "server"), "locally disabled: whitelist"));
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
    @DisplayName("the bot and tunnel rows")
    class BotAndTunnel {

        @Test
        @DisplayName("healthy reads linked and connected, both green, and raises nothing")
        void allGreen() {
            StatusSubcommand.Snapshot s = healthy();

            StatusSubcommand.Report report = StatusSubcommand.assess(s);

            assertEquals(StatusSubcommand.Health.OK, row(report, "Bot").health);
            assertEquals("linked, guild 1342530243746857112", row(report, "Bot").value);
            assertEquals(StatusSubcommand.Health.OK, row(report, "Tunnel").health);
            assertEquals("protocol v3, config v8", row(report, "Tunnel").value);
            assertTrue(report.problems.isEmpty(), "healthy has no problem lines");
        }

        @Test
        @DisplayName("the four discovery states each read differently")
        void discoveryStates() {
            StatusSubcommand.Snapshot refused = healthy();
            refused.discovery = GuildDiscovery.Status.TOKEN_REFUSED;
            StatusSubcommand.Snapshot unreachable = healthy();
            unreachable.discovery = GuildDiscovery.Status.UNREACHABLE;
            StatusSubcommand.Snapshot discovering = healthy();
            discovering.discovery = GuildDiscovery.Status.DISCOVERING;
            StatusSubcommand.Snapshot unconfigured = healthy();
            unconfigured.configured = false;

            assertRow(refused, "Bot", StatusSubcommand.Health.BROKEN, "token refused");
            assertTrue(problemSaying(StatusSubcommand.assess(refused), "/hd setup <new code>"));
            assertRow(unreachable, "Bot", StatusSubcommand.Health.BROKEN, "unreachable");
            assertTrue(problemSaying(StatusSubcommand.assess(unreachable), "Cannot reach the bot"));
            assertRow(discovering, "Bot", StatusSubcommand.Health.ATTENTION, "finding its guild");
            assertTrue(problemSaying(StatusSubcommand.assess(discovering), "which guild"));
            assertRow(unconfigured, "Bot", StatusSubcommand.Health.BROKEN, "not set up");
        }

        @Test
        @DisplayName("a blocking identity mismatch is red and says /hd identity")
        void identityBlocks() {
            StatusSubcommand.Snapshot s = healthy();
            s.identityMismatch = true;
            s.identityBlocks = true;
            s.tunnelConnected = false;

            assertRow(s, "Bot", StatusSubcommand.Health.BROKEN, "blocked: identity mismatch");
            assertTrue(problemSaying(StatusSubcommand.assess(s), "another machine: /hd identity"));
            assertRow(s, "Tunnel", StatusSubcommand.Health.IDLE, "waiting on the bot");
        }

        @Test
        @DisplayName("an advisory mismatch is raised even while discovery is failing")
        void advisoryMismatchSurvivesDiscovery() {
            StatusSubcommand.Snapshot s = healthy();
            s.identityMismatch = true;
            s.discovery = GuildDiscovery.Status.TOKEN_REFUSED;

            StatusSubcommand.Report report = StatusSubcommand.assess(s);

            assertTrue(problemSaying(report, "Credentials bound to another machine"),
                    "a copied directory whose twin re-ran setup shows up as a refused token, and "
                            + "the mismatch is the explanation");
            assertTrue(problemSaying(report, "Token refused"));
        }

        @Test
        @DisplayName("an advisory mismatch on a healthy server turns the bot row yellow")
        void advisoryMismatchHealthy() {
            StatusSubcommand.Snapshot s = healthy();
            s.identityMismatch = true;

            assertRow(s, "Bot", StatusSubcommand.Health.ATTENTION, "linked");
        }

        @Test
        @DisplayName("a whitelabel endpoint is named; the default one is not")
        void whitelabel() {
            StatusSubcommand.Snapshot s = healthy();
            s.endpointHost = StatusSubcommand.endpointHost("https://bot.example.com/");

            assertRow(s, "Bot", StatusSubcommand.Health.OK, "linked via bot.example.com");
            s.endpointHost = "minecraft-bot.a-very-long-customer-domain.example";
            assertTrue(row(StatusSubcommand.assess(s), "Bot").value.length() <= 50,
                    "a long host is abbreviated, not wrapped");
            assertEquals("", StatusSubcommand.endpointHost("https://api.bifrost.gg"));
            assertEquals("", StatusSubcommand.endpointHost("HTTPS://API.BIFROST.GG/"));
            assertEquals("", StatusSubcommand.endpointHost("http://api.bifrost.gg/v1"));
            assertEquals("", StatusSubcommand.endpointHost(""));
        }

        @Test
        @DisplayName("an API that is not ready behind a linked bot is flagged")
        void apiNotReady() {
            StatusSubcommand.Snapshot s = healthy();
            s.apiReady = false;
            s.apiDescribe = "discovering this token's guild";

            assertRow(s, "Bot", StatusSubcommand.Health.ATTENTION, "linked");
            assertTrue(problemSaying(StatusSubcommand.assess(s), "Bot API: discovering"));
        }

        @Test
        @DisplayName("a down tunnel behind a linked bot is red; with no guild it is grey")
        void tunnelDown() {
            StatusSubcommand.Snapshot down = healthy();
            down.tunnelConnected = false;
            StatusSubcommand.Snapshot incomplete = healthy();
            incomplete.tunnelConnected = false;
            incomplete.tunnelConfigured = false;

            assertRow(down, "Tunnel", StatusSubcommand.Health.BROKEN, "not connected");
            assertTrue(problemSaying(StatusSubcommand.assess(down), "Live link to the bot is down"));
            assertRow(incomplete, "Tunnel", StatusSubcommand.Health.BROKEN, "settings incomplete");
            assertTrue(problemSaying(StatusSubcommand.assess(incomplete), "/hd setup <new code>"));
        }

        @Test
        @DisplayName("a connected tunnel is judged even while the guild is being rediscovered")
        void connectedWhileDiscovering() {
            StatusSubcommand.Snapshot s = healthy();
            s.discovery = GuildDiscovery.Status.DISCOVERING;
            s.mode = ProtocolMode.V2_COMPAT;

            assertRow(s, "Tunnel", StatusSubcommand.Health.BROKEN, "v2-compat");
        }

        @Test
        @DisplayName("v2-compat and a missing config are told apart from healthy")
        void protocol() {
            StatusSubcommand.Snapshot compat = healthy();
            compat.mode = ProtocolMode.V2_COMPAT;
            StatusSubcommand.Snapshot noConfig = healthy();
            noConfig.configVersion = -1;

            assertRow(compat, "Tunnel", StatusSubcommand.Health.BROKEN, "protocol v2-compat");
            assertTrue(problemSaying(StatusSubcommand.assess(compat), "only speaks v2"));
            assertRow(noConfig, "Tunnel", StatusSubcommand.Health.ATTENTION, "no config yet");
            assertTrue(problemSaying(StatusSubcommand.assess(noConfig), "send this server's settings"));
        }

        @Test
        @DisplayName("dropped console readers are reported here, because nowhere else can")
        void droppedTap() {
            StatusSubcommand.Snapshot s = healthy();
            s.droppedTapConsumers = 2;

            StatusSubcommand.Report report = StatusSubcommand.assess(s);

            assertTrue(problemSaying(report, "2 console reader(s) dropped"));
            assertEquals(StatusSubcommand.Health.BROKEN, report.ordered().get(0).health);
        }
    }

    @Nested
    @DisplayName("the modules and updates rows")
    class ModulesAndUpdates {

        @Test
        @DisplayName("counts running and off, and names a locally disabled module as off")
        void counts() {
            StatusSubcommand.Snapshot s = healthy();
            s.modules.put("whitelist", ModuleState.ENABLED);
            s.modules.put("bridge", ModuleState.ENABLED);
            s.modules.put("console", ModuleState.STOPPED);
            s.modules.put("health", ModuleState.INELIGIBLE);
            s.locallyOff.add("bridge");

            assertRow(s, "Modules", StatusSubcommand.Health.ATTENTION, "1 running, 2 off");
            assertTrue(problemSaying(StatusSubcommand.assess(s), "Locally disabled: bridge"));
        }

        @Test
        @DisplayName("an available update is yellow and says the command")
        void updateAvailable() {
            StatusSubcommand.Snapshot s = healthy();
            s.updatesSupported = true;
            s.updateAvailable = true;
            s.latestVersion = "3.0.0-rc.99";

            assertRow(s, "Updates", StatusSubcommand.Health.ATTENTION, "3.0.0-rc.99 available");
            assertTrue(problemSaying(StatusSubcommand.assess(s), "3.0.0-rc.99 is out: /hd update"));
        }
    }

    @Nested
    @DisplayName("the mirror row")
    class Mirror {

        @Test
        @DisplayName("shows the size and the age of the last sync")
        void fresh() {
            StatusSubcommand.Snapshot s = withMirror(3072, minutesAgo(4), TimeUnit.MINUTES.toMillis(5));

            assertRow(s, "Mirror", StatusSubcommand.Health.OK, "3,072 players, synced 4m ago");
        }

        @Test
        @DisplayName("goes yellow after three missed polls")
        void stale() {
            StatusSubcommand.Snapshot s = withMirror(10, minutesAgo(120), TimeUnit.MINUTES.toMillis(5));

            assertRow(s, "Mirror", StatusSubcommand.Health.ATTENTION, "synced 2h ago");
            assertTrue(problemSaying(StatusSubcommand.assess(s),
                    "Mirror synced 2h ago, due every 5m: /hd cache sync"));
            // Two missed hourly polls is not yet stale; three is.
            assertRow(withMirror(10, minutesAgo(150), TimeUnit.HOURS.toMillis(1)), "Mirror",
                    StatusSubcommand.Health.OK, "synced");
            assertRow(withMirror(10, minutesAgo(190), TimeUnit.HOURS.toMillis(1)), "Mirror",
                    StatusSubcommand.Health.ATTENTION, "synced");
        }

        @Test
        @DisplayName("is not called stale for one slow poll on a short interval")
        void floor() {
            assertRow(withMirror(10, minutesAgo(10), TimeUnit.MINUTES.toMillis(1)), "Mirror",
                    StatusSubcommand.Health.OK, "synced 10m ago");
        }

        @Test
        @DisplayName("a mirror that has not synced since start says so")
        void neverSynced() {
            StatusSubcommand.Snapshot s = withMirror(0, 0L, TimeUnit.MINUTES.toMillis(5));

            assertRow(s, "Mirror", StatusSubcommand.Health.ATTENTION, "0 players, not synced since start");
            assertTrue(problemSaying(StatusSubcommand.assess(s), "/hd cache sync"));
        }

        @Test
        @DisplayName("raises nothing while the bot is the problem, since a sync cannot help")
        void botDown() {
            StatusSubcommand.Snapshot s = withMirror(10, minutesAgo(120), TimeUnit.MINUTES.toMillis(5));
            s.discovery = GuildDiscovery.Status.UNREACHABLE;

            StatusSubcommand.Report report = StatusSubcommand.assess(s);

            assertEquals(StatusSubcommand.Health.IDLE, row(report, "Mirror").health);
            assertFalse(problemSaying(report, "cache sync"));
        }

        @Test
        @DisplayName("with the pre-warm off, its age means nothing and is not judged")
        void prewarmOff() {
            StatusSubcommand.Snapshot s = withMirror(1, 0L, 0L);

            assertRow(s, "Mirror", StatusSubcommand.Health.OK, "1 player, pre-warm off");
            assertFalse(problemSaying(StatusSubcommand.assess(s), "Mirror"));
        }

        @Test
        @DisplayName("is grey with no whitelist module running")
        void absent() {
            assertRow(healthy(), "Mirror", StatusSubcommand.Health.IDLE, "not used");
        }
    }

    @Nested
    @DisplayName("rendering")
    class Rendering {

        @Test
        @DisplayName("every row and the footer words run their view when clicked, with the label")
        void clickable() {
            StatusSubcommand.Snapshot s = healthy();
            s.label = "hdp";

            List<Component> lines = StatusSubcommand.render(s);

            assertEquals("/hdp status bot", clickOf(lines.get(1)), "the Bot row");
            assertEquals("/hdp status modules", clickOf(lines.get(3)), "the Modules row");
            assertEquals("/hdp status server", clickOf(lines.get(5)), "the Updates row");
            Component footer = lines.get(lines.size() - 1);
            List<String> clicks = new ArrayList<String>();
            for (Component child : footer.children()) {
                String click = clickOf(child);
                if (click != null) {
                    clicks.add(click);
                }
            }
            assertEquals(Arrays.asList("/hdp status bot", "/hdp status modules",
                    "/hdp status server", "/hdp status all"), clicks);
        }

        @Test
        @DisplayName("the header names version, role and the short software")
        void header() {
            StatusSubcommand.Snapshot s = healthy();

            String header = TestText.plain(StatusSubcommand.render(s).get(0));

            assertEquals("Heimdall 3.0.0-rc.22 \u00B7 enforcer \u00B7 Purpur 26.2", header);
        }

        @Test
        @DisplayName("healthy is seven lines; three problems print in full")
        void lengths() {
            assertEquals(7, StatusSubcommand.render(healthy()).size());

            StatusSubcommand.Snapshot three = healthy();
            three.mode = ProtocolMode.V2_COMPAT;
            three.droppedTapConsumers = 1;
            three.identityMismatch = true;
            List<Component> lines = StatusSubcommand.render(three);
            assertEquals(10, lines.size());
            assertFalse(TestText.plain(lines.get(8)).contains("more"));
        }

        @Test
        @DisplayName("broken problems print before attention ones")
        void ordering() {
            StatusSubcommand.Snapshot s = healthy();
            s.identityMismatch = true;
            s.droppedTapConsumers = 1;

            List<Component> lines = StatusSubcommand.render(s);

            assertTrue(TestText.plain(lines.get(6)).contains("console reader"), TestText.plain(lines.get(6)));
            assertTrue(TestText.plain(lines.get(7)).contains("another machine"));
        }

        @Test
        @DisplayName("every row and problem line stays short enough to read at chat width")
        void shortLines() {
            List<StatusSubcommand.Snapshot> states = new ArrayList<StatusSubcommand.Snapshot>();
            for (GuildDiscovery.Status discovery : GuildDiscovery.Status.values()) {
                states.add(stateWith(discovery == GuildDiscovery.Status.RESOLVED ? null : discovery));
            }
            StatusSubcommand.Snapshot unconfigured = healthy();
            unconfigured.configured = false;
            states.add(unconfigured);
            StatusSubcommand.Snapshot blocked = healthy();
            blocked.identityBlocks = true;
            states.add(blocked);
            StatusSubcommand.Snapshot down = healthy();
            down.tunnelConnected = false;
            states.add(down);
            StatusSubcommand.Snapshot incomplete = healthy();
            incomplete.tunnelConnected = false;
            incomplete.tunnelConfigured = false;
            states.add(incomplete);
            StatusSubcommand.Snapshot compat = healthy();
            compat.mode = ProtocolMode.V2_COMPAT;
            states.add(compat);
            StatusSubcommand.Snapshot noConfig = healthy();
            noConfig.configVersion = -1;
            noConfig.apiReady = false;
            noConfig.apiDescribe = "discovering this token's guild";
            states.add(noConfig);
            states.add(withMirror(123456, minutesAgo(3000), TimeUnit.MINUTES.toMillis(5)));
            states.add(withMirror(123456, 0L, TimeUnit.MINUTES.toMillis(5)));
            StatusSubcommand.Snapshot busy = healthy();
            busy.label = "hdp";
            busy.identityMismatch = true;
            busy.droppedTapConsumers = 12;
            busy.updatesSupported = true;
            busy.updateAvailable = true;
            busy.latestVersion = "3.0.0-rc.123";
            for (String id : Arrays.asList("whitelist", "punishments", "rolesync", "chatchannels")) {
                busy.modules.put(id, ModuleState.FAILED);
            }
            busy.locallyOff.addAll(Arrays.asList("whitelist", "punishments", "rolesync"));
            busy.modules.put("itemimages", ModuleState.FAILED);
            states.add(busy);

            for (StatusSubcommand.Snapshot s : states) {
                StatusSubcommand.Report report = StatusSubcommand.assess(s);
                for (StatusSubcommand.Row row : report.rows) {
                    String line = row.label + " " + row.value;
                    assertTrue(line.length() <= 55, "row wraps: " + line);
                }
                for (StatusSubcommand.Problem problem : report.problems) {
                    assertTrue(problem.text.length() <= 60, "problem wraps: " + problem.text);
                }
            }
        }
    }

    @Nested
    @DisplayName("reading a live runtime")
    class Reading {

        @Test
        @DisplayName("Snapshot.of carries what an unconfigured runtime says")
        void unconfigured() {
            registerModule("bridge", false);
            install(context());
            say("disable", "bridge");

            StatusSubcommand.Snapshot s = StatusSubcommand.Snapshot.of(context().label("hdp").build());

            assertEquals("hdp", s.label);
            assertEquals("standalone", s.role);
            assertEquals(BuildConstants.VERSION, s.pluginVersion);
            assertFalse(s.configured);
            assertFalse(s.apiReady, "an unconfigured API is not ready");
            assertEquals("not set up", s.apiDescribe);
            assertFalse(s.tunnelConnected);
            assertFalse(s.tunnelConfigured, "an unconfigured tunnel has no settings");
            assertEquals(null, s.discovery, "nothing to discover without credentials");
            assertTrue(s.modules.containsKey("bridge"));
            assertTrue(s.locallyOff.contains("bridge"));
            assertEquals(0, s.droppedTapConsumers);
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
            assertTrue(anyContains(lines, "endpoint: none"), lines.toString());
            assertTrue(anyContains(lines, "status: " + runtime.connectionStatus()),
                    "the full sentence behind the short Bot row lives here");
            assertTrue(anyContains(lines, "api: not set up"));
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
    @DisplayName("a role that changes at the next restart")
    class RoleAfterRestart {

        @Test
        @DisplayName("is one attention line that says what and why")
        void saysRestart() {
            StatusSubcommand.Snapshot s = healthy();
            s.nextRole = "standalone";
            s.nextRoleReason = "no Heimdall proxy in this guild";

            StatusSubcommand.Report report = StatusSubcommand.assess(s);

            assertTrue(problemSaying(report,
                    "Restart to run as standalone: no Heimdall proxy in this guild"),
                    report.problems.toString());
        }

        @Test
        @DisplayName("a live enforcer with a learned standalone says restart, in the summary and in server")
        void readFromALiveRuntime() throws java.io.IOException {
            runtime.close();
            BootstrapStore store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
            store.save(BootstrapConfig.builder().learnedRole(ServerRole.STANDALONE).build());
            runtime = HeimdallRuntime.builder(logger, platform)
                    .bootstrapStore(store)
                    .roleDetector(new com.heimdall.core.platform.InstanceRoleDetector() {
                        @Override
                        public boolean isProxy() {
                            return false;
                        }

                        @Override
                        public boolean isBehindProxy() {
                            return true;
                        }
                    })
                    .build();
            install(context().role(ServerRole.ENFORCER));

            assertTrue(anyContains(say("status"),
                    "Restart to run as standalone: no Heimdall proxy in this guild"));
            assertTrue(anyContains(say("status", "server"),
                    "role after restart: standalone (no Heimdall proxy in this guild)"));
        }

        @Test
        @DisplayName("names the proxy only when the bot said so, and the file otherwise")
        void reasonFollowsTheEvidence() {
            BootstrapConfig learned = BootstrapConfig.builder()
                    .learnedRole(ServerRole.STANDALONE).build();
            BootstrapConfig plain = BootstrapConfig.defaults();
            BootstrapConfig configured = BootstrapConfig.builder().role(ServerRole.ENFORCER).build();

            assertEquals("no Heimdall proxy in this guild",
                    StatusSubcommand.nextRoleReason(learned, ServerRole.STANDALONE, Boolean.FALSE));
            assertEquals("a Heimdall proxy is registered in this network",
                    StatusSubcommand.nextRoleReason(plain, ServerRole.ENFORCER, Boolean.TRUE));
            assertEquals("bootstrap.yml changed",
                    StatusSubcommand.nextRoleReason(plain, ServerRole.ENFORCER, null),
                    "a hand-deleted learnedRole is not a proxy joining");
            assertEquals("set in bootstrap.yml",
                    StatusSubcommand.nextRoleReason(configured, ServerRole.ENFORCER, Boolean.TRUE));
        }

        @Test
        @DisplayName("says nothing when the next start is the same role")
        void quietWhenUnchanged() {
            StatusSubcommand.Report report = StatusSubcommand.assess(healthy());

            assertFalse(problemSaying(report, "Restart to run as"));
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

    /** A configured, linked, connected server with nothing wrong, and no optional parts. */
    private static StatusSubcommand.Snapshot healthy() {
        StatusSubcommand.Snapshot s = new StatusSubcommand.Snapshot();
        s.pluginVersion = "3.0.0-rc.22";
        s.role = "enforcer";
        s.software = "Purpur 26.2";
        s.guildId = "1342530243746857112";
        s.configVersion = 8;
        return s;
    }

    private static StatusSubcommand.Snapshot stateWith(GuildDiscovery.Status discovery) {
        StatusSubcommand.Snapshot s = healthy();
        s.discovery = discovery;
        return s;
    }

    private static StatusSubcommand.Snapshot withMirror(int entries, long lastSyncAtMs, long intervalMs) {
        StatusSubcommand.Snapshot s = healthy();
        s.whitelistAvailable = true;
        s.mirrorEntries = entries;
        s.lastSyncAtMs = lastSyncAtMs;
        s.syncIntervalMs = intervalMs;
        return s;
    }

    private static void assertRow(StatusSubcommand.Snapshot s, String label,
            StatusSubcommand.Health health, String valuePart) {
        StatusSubcommand.Row row = row(StatusSubcommand.assess(s), label);
        assertEquals(health, row.health, label + " row: " + row.value);
        assertTrue(row.value.contains(valuePart), label + " row was \"" + row.value + "\"");
    }

    private static String clickOf(Component component) {
        ClickEvent click = component.clickEvent();
        return click == null ? null : click.value();
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
