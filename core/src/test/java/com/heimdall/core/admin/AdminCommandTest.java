package com.heimdall.core.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.BuildConstants;
import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.model.OffenseType;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.testing.RecordingCommands;
import com.heimdall.core.util.Registration;
import com.heimdall.core.wiring.HeimdallRuntime;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The admin command tree, driven the way a console drives it.
 *
 * <p>The reason this file can exist at all is the reason the tree was moved into core: v2's was a
 * {@code switch} inside a 1,086-line {@code JavaPlugin}, so the only way to find out whether
 * {@code /hwl cache cleanup} worked on the proxy was to start a proxy — and it did not, for a year,
 * because the branch was never written there. Here every verb takes a {@link FakeCommandSource} and
 * says what it said.
 *
 * <p>The tests below are about the things that would otherwise be silently wrong: a verb that is
 * registered but unreachable, a permission gate that does not gate, a subcommand missing on one
 * platform, and a deprecated alias that either nags on every use or never mentions itself at all.
 */
class AdminCommandTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    @TempDir
    Path dataDir;

    private FakePlatform platform;
    private HeimdallRuntime runtime;
    private BootstrapStore store;
    private Registration installed = Registration.NONE;
    private FakeCommandSource admin;

    @BeforeEach
    void setUp() {
        platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        store = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml"));
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

    private void install() {
        install(context());
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

    @Nested
    @DisplayName("registration")
    class RegistrationShape {

        @Test
        @DisplayName("claims the verb, its spelled-out alias, and the deprecated v2 name")
        void registersBothNames() {
            install();

            assertTrue(commands().has("hd"));
            assertTrue(commands().has(AdminCommand.DEPRECATED_NAME),
                    "every v2 install's runbooks and staff macros say /hwl; removing it outright "
                            + "makes 'Unknown command' the first thing a migrating server sees");
            assertEquals(Collections.singletonList("heimdall"),
                    commands().spec("hd").aliases());
            assertEquals(AdminCommand.PERMISSION, commands().spec("hd").permission());
            assertEquals(AdminCommand.PERMISSION,
                    commands().spec(AdminCommand.DEPRECATED_NAME).permission(),
                    "the alias must be gated exactly as the real verb is, or it is a way round it");
        }

        @Test
        @DisplayName("closing the handle takes both away")
        void unregisters() {
            install();

            installed.close();
            installed = Registration.NONE;

            assertFalse(commands().has("hd"));
            assertFalse(commands().has(AdminCommand.DEPRECATED_NAME));
        }

        @Test
        @DisplayName("a sender without the node is refused before any handler runs")
        void permissionGated() {
            install();
            FakeCommandSource nobody = FakeCommandSource.player("Griefer");

            assertFalse(commands().run(nobody, "hd", "status"),
                    "the registrar gates it, so no subcommand has to re-check — one gate, at the door");
            assertTrue(nobody.messageText().isEmpty());
        }

        @Test
        @DisplayName("the console can run everything, which is what the setup flow needs")
        void consoleIsAllowed() {
            install();
            FakeCommandSource console = FakeCommandSource.console();

            assertTrue(commands().run(console, "hd", "status"));
            assertFalse(console.messageText().isEmpty());
        }
    }

    @Nested
    @DisplayName("dispatch")
    class Dispatch {

        @Test
        @DisplayName("no arguments lists every verb, and the deprecated alias is not one of them")
        void helpListsTheTree() {
            install();

            List<String> lines = say();

            for (String verb : Arrays.asList("setup", "status", "reload", "modules", "test",
                    "cache", "offense", "version", "update", "debug")) {
                assertTrue(anyContains(lines, "/hd " + verb), "help should mention " + verb
                        + " but said: " + lines);
            }
            assertFalse(anyContains(lines, "/hd hwl"),
                    "help is for what to type next, not for what used to work");
        }

        @Test
        @DisplayName("an unknown verb says which one, then shows the tree")
        void unknownVerb() {
            install();

            List<String> lines = say("wibble");

            assertTrue(anyContains(lines, "No such subcommand"));
            assertTrue(anyContains(lines, "wibble"), "naming it is the difference between a typo "
                    + "an operator can see and a command they think is broken");
            assertTrue(anyContains(lines, "/hd status"));
        }

        @Test
        @DisplayName("the verb is matched case-insensitively, as a console will type it")
        void caseInsensitive() {
            install();

            assertTrue(anyContains(say("STATUS"), "Heimdall"));
        }
    }

    @Nested
    @DisplayName("the deprecated /hwl alias")
    class DeprecatedAlias {

        @Test
        @DisplayName("says the name changed once per start, then forwards quietly")
        void warnsOnceThenForwards() {
            install();

            admin.clearMessages();
            assertTrue(commands().run(admin, AdminCommand.DEPRECATED_NAME, "status"));
            List<String> first = new ArrayList<String>(admin.messageText());

            admin.clearMessages();
            assertTrue(commands().run(admin, AdminCommand.DEPRECATED_NAME, "status"));
            List<String> second = new ArrayList<String>(admin.messageText());

            assertTrue(anyContains(first, "v2's name"),
                    "somebody has to be told the verb moved: " + first);
            assertTrue(anyContains(first, "/hd"), "and told what to type instead");
            assertFalse(anyContains(second, "v2's name"),
                    "a notice attached to every use is one people learn to scroll past");
            assertTrue(anyContains(second, "Heimdall"),
                    "and it still has to actually run the command");
        }
    }

    @Nested
    @DisplayName("status")
    class Status {

        @Test
        @DisplayName("names every state an operator confuses, on an unconfigured server")
        void unconfigured() {
            install();

            List<String> lines = say("status");

            assertTrue(anyContains(lines, BuildConstants.VERSION));
            assertTrue(anyContains(lines, "standalone"), "the RESOLVED role, not the configured one");
            assertTrue(anyContains(lines, "not set up"),
                    "'not set up' and 'set up but unreachable' are different conversations");
            assertTrue(anyContains(lines, "whitelist mirror"));
            assertTrue(anyContains(lines, "console tap"),
                    "the dropped-consumer count is reported here or nowhere — the drop cannot log "
                            + "for itself");
            assertTrue(anyContains(lines, "no self-updater in this build"));
        }

        @Test
        @DisplayName("reports what the module surfaces say when they are installed")
        void withModuleSurfaces() {
            install(context()
                    .whitelist(new FakeWhitelist("41 entries (0 expired), etag=abc, last synced 3s ago"))
                    .offenses(new FakeOffenses()));

            List<String> lines = say("status");

            assertTrue(anyContains(lines, "41 entries"));
            assertTrue(anyContains(lines, "last synced"),
                    "the age is the part that answers the question: 41 entries looks equally healthy "
                            + "whether the last successful pull was a minute or a week ago");
        }
    }

    @Nested
    @DisplayName("verbs that need a module")
    class WithoutModules {

        @Test
        @DisplayName("say the feature is not installed rather than doing nothing")
        void nonePathsAreHonest() {
            install();

            assertTrue(anyContains(say("cache", "stats"), "not running"));
            assertTrue(anyContains(say("test", "Steve"), "not running"));
            assertTrue(anyContains(say("offense", "types"), "not running"));
            assertTrue(anyContains(say("update"), "no self-updater"));
        }

        @Test
        @DisplayName("test with no argument shows its usage instead of probing nobody")
        void testNeedsAName() {
            install();

            assertTrue(anyContains(say("test"), "/hd test <player>"));
        }
    }

    @Nested
    @DisplayName("cache")
    class Cache {

        @Test
        @DisplayName("stats, clear and cleanup all reach the module — on every platform")
        void allThreeVerbsExist() {
            FakeWhitelist whitelist = new FakeWhitelist("2 entries (0 expired), etag=none");
            install(context().whitelist(whitelist));

            assertTrue(anyContains(say("cache", "stats"), "2 entries"));
            assertTrue(anyContains(say("cache", "clear"), "emptied"));
            assertTrue(anyContains(say("cache", "cleanup"), "Swept"));

            assertEquals(1, whitelist.cleared);
            assertEquals(1, whitelist.cleanups,
                    "v2's proxy build never grew a cleanup branch, and nothing anywhere said so");
        }

        @Test
        @DisplayName("clear says what it costs, because the cost is delayed")
        void clearWarnsAboutTheConsequence() {
            install(context().whitelist(new FakeWhitelist("2 entries")));

            assertTrue(anyContains(say("cache", "clear"), "refuse every player"),
                    "with the default whitelist-only fallback an empty mirror locks everybody out "
                            + "the next time the bot hiccups, and the poll that refills it is minutes away");
        }

        @Test
        @DisplayName("an unknown cache verb prints the four that exist")
        void unknownSubVerb() {
            install(context().whitelist(new FakeWhitelist("2 entries")));

            assertTrue(anyContains(say("cache", "nope"), "stats|clear|cleanup|sync"));
        }
    }

    @Nested
    @DisplayName("debug")
    class Debug {

        @Test
        @DisplayName("flips the logger and remembers it in bootstrap.yml")
        void togglesAndPersists() {
            install();

            assertTrue(anyContains(say("debug", "on"), "now"));

            assertTrue(logger.isDebugEnabled());
            BootstrapConfig onDisk = new BootstrapStore(logger, dataDir.resolve("bootstrap.yml")).load();
            assertTrue(onDisk.debug(),
                    "an operator turning debug on to catch an intermittent problem should not lose "
                            + "it the first time the server cycles");

            say("debug", "off");
            assertFalse(logger.isDebugEnabled());
        }

        @Test
        @DisplayName("a word that is neither on nor off is refused rather than guessed at")
        void refusesNonsense() {
            install();

            assertTrue(anyContains(say("debug", "maybe"), "on"));
            assertTrue(anyContains(say("debug", "maybe"), "off"));
        }

        @Test
        @DisplayName("with no argument it reports the current setting")
        void reportsCurrent() {
            install();

            assertTrue(anyContains(say("debug"), "currently"));
        }
    }

    @Nested
    @DisplayName("completion")
    class Completion {

        @Test
        @DisplayName("suggests verbs, filtered by what has been typed")
        void topLevel() {
            install();

            assertTrue(commands().complete(admin, "hd").contains("status"));
            assertEquals(Collections.singletonList("setup"), commands().complete(admin, "hd", "set"));
            assertTrue(commands().complete(admin, "hd", "zzz").isEmpty());
        }

        @Test
        @DisplayName("suggests a subcommand's own arguments")
        void secondLevel() {
            install(context().whitelist(new FakeWhitelist("0 entries")));

            assertTrue(commands().complete(admin, "hd", "cache", "").contains("cleanup"));
            assertEquals(Collections.singletonList("clear"),
                    commands().complete(admin, "hd", "cache", "cl").stream()
                            .filter(s -> s.equals("clear")).collect(java.util.stream.Collectors.toList()));
            assertTrue(commands().complete(admin, "hd", "debug", "o").containsAll(
                    Arrays.asList("on", "off")));
        }

        @Test
        @DisplayName("offers nothing to a sender who could not run it")
        void gatedByPermission() {
            install();

            assertTrue(commands().complete(FakeCommandSource.player("Griefer"), "hd").isEmpty(),
                    "a command somebody cannot run has no business advertising its arguments");
        }
    }

    @Nested
    @DisplayName("setup")
    class Setup {

        @Test
        @DisplayName("with no code it says where to get one")
        void needsACode() {
            install();

            List<String> lines = say("setup");

            assertTrue(anyContains(lines, "/hd setup <code> [endpoint]"));
            assertTrue(anyContains(lines, "dashboard"),
                    "the operator's next step is minting a code, not reading the source");
        }

        @Test
        @DisplayName("acknowledges before it blocks, naming the endpoint it will use")
        void acknowledgesFirst() {
            install();

            List<String> lines = say("setup", "ABCD2345");

            assertTrue(anyContains(lines, SetupSubcommand.DEFAULT_ENDPOINT),
                    "with nothing configured it falls back to the public bot, and says so — a claim "
                            + "against the wrong instance is the whitelabel failure mode");
        }

        @Test
        @DisplayName("an explicit endpoint wins, which is the whitelabel answer")
        void endpointArgumentWins() {
            install();

            List<String> lines = say("setup", "ABCD2345", "https://mc.example.test");

            assertTrue(anyContains(lines, "https://mc.example.test"));
            assertFalse(anyContains(lines, SetupSubcommand.DEFAULT_ENDPOINT));
        }
    }

    // ── Fakes ────────────────────────────────────────────────────────────────

    /** A whitelist module that is installed and counts what was asked of it. */
    private static final class FakeWhitelist implements WhitelistAdmin {

        private final String stats;
        int cleared;
        int cleanups;

        FakeWhitelist(String stats) {
            this.stats = stats;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String stats() {
            return stats;
        }

        @Override
        public void clear() {
            cleared++;
        }

        @Override
        public int cleanup() {
            cleanups++;
            return 3;
        }

        @Override
        public void syncNow() {
        }

        @Override
        public LoginProbe probe(String playerName) {
            return LoginProbe.forPlayer(playerName, "00000000-0000-0000-0000-000000000000")
                    .allowed(false)
                    .stage("the bot says they are not whitelisted")
                    .message("You are not whitelisted on this server.")
                    .build();
        }
    }

    /** An offenses module with one type, one of which is disabled. */
    private static final class FakeOffenses implements OffenseAdmin {

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void reload() {
        }

        @Override
        public List<OffenseType> types() {
            return Collections.singletonList(OffenseType.builder()
                    .typeId("cheating")
                    .displayName("Cheating")
                    .offenses(Arrays.asList("xray", "fly"))
                    .enabled(true)
                    .build());
        }
    }
}
