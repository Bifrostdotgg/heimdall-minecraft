package com.heimdall.core.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.remoteconfig.ConfigDocument;
import com.heimdall.core.remoteconfig.RemoteConfig;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.tunnel.Capabilities;
import com.heimdall.core.tunnel.ProtocolMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The lifecycle: reconciliation, mechanical unwinding, failure containment, role eligibility. */
class ModuleManagerTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    @TempDir
    Path dataDir;

    private HeimdallExecutors executors;
    private LoginPipeline loginPipeline;
    private ChatPipeline chatPipeline;
    private RemoteConfig remoteConfig;

    @BeforeEach
    void setUp() {
        executors = new HeimdallExecutors(logger, 1);
        loginPipeline = new LoginPipeline(logger);
        chatPipeline = new ChatPipeline(logger);
        remoteConfig = new RemoteConfig(logger, dataDir.resolve("remote-config.json"),
                ConfigDocument.empty());
    }

    @AfterEach
    void tearDown() {
        executors.shutdown(1000);
    }

    private ModuleManager manager(ServerRole role) {
        return new ModuleManager(ModuleEnvironment.builder()
                .logger(logger)
                .executors(executors)
                .remoteConfig(remoteConfig)
                .loginPipeline(loginPipeline)
                .chatPipeline(chatPipeline)
                .platform(new FakePlatform(role, dataDir))
                .build());
    }

    private static Set<String> desire(String... ids) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(ids)));
    }

    // ── Reconciliation ───────────────────────────────────────────────────────

    @Test
    @DisplayName("reconcile starts what is wanted and stops what is not")
    void reconcileDiffsBothWays() {
        ModuleManager manager = manager(ServerRole.STANDALONE);
        RecordingModule whitelist = new RecordingModule("whitelist");
        RecordingModule console = new RecordingModule("console");
        manager.register(whitelist);
        manager.register(console);

        manager.reconcile(desire("whitelist"));
        assertEquals(ModuleState.ENABLED, manager.state("whitelist"));
        assertEquals(ModuleState.STOPPED, manager.state("console"));

        manager.reconcile(desire("console"));
        assertEquals(ModuleState.STOPPED, manager.state("whitelist"));
        assertEquals(ModuleState.ENABLED, manager.state("console"));
        assertEquals(1, whitelist.disableCalls());
    }

    @Test
    @DisplayName("reconciling with an unchanged set does not restart anything")
    void reconcileIsIdempotent() {
        ModuleManager manager = manager(ServerRole.STANDALONE);
        RecordingModule whitelist = new RecordingModule("whitelist");
        manager.register(whitelist);

        manager.reconcile(desire("whitelist"));
        manager.reconcile(desire("whitelist"));
        manager.reconcile(desire("whitelist"));

        assertEquals(1, whitelist.enableCalls(),
                "a config push that changes something else must not bounce every running module");
        assertEquals(0, whitelist.disableCalls());
    }

    @Test
    @DisplayName("modules enable in registration order and stop in reverse")
    void orderIsRegistrationOrder() {
        ModuleManager manager = manager(ServerRole.STANDALONE);
        final List<String> order = new ArrayList<String>();
        manager.register(new OrderedModule("first", order));
        manager.register(new OrderedModule("second", order));

        manager.reconcile(desire("first", "second"));
        assertEquals(Arrays.asList("enable:first", "enable:second"), order);

        order.clear();
        manager.shutdown();
        assertEquals(Arrays.asList("disable:second", "disable:first"), order,
                "reverse order on the way out, for the same reason a stack unwinds that way");
    }

    // ── Mechanical unwinding ─────────────────────────────────────────────────

    @Test
    @DisplayName("disabling unwinds everything the module registered, even though it undid nothing")
    void disableUnwindsRegistrationsTheModuleForgot() {
        ModuleManager manager = manager(ServerRole.STANDALONE);
        manager.register(new RecordingModule("whitelist").registerEverything());

        manager.reconcile(desire("whitelist"));
        assertEquals(1, loginPipeline.size());
        assertEquals(1, chatPipeline.size());
        assertEquals(1, chatPipeline.observerCount());

        manager.reconcile(Collections.<String>emptySet());

        assertEquals(0, loginPipeline.size(),
                "a 'disabled' module still gating logins is the exact failure this design prevents");
        assertEquals(0, chatPipeline.size());
        assertEquals(0, chatPipeline.observerCount());
    }

    @Test
    @DisplayName("re-enabling registers once, not twice")
    void reEnablingDoesNotDoubleRegister() {
        ModuleManager manager = manager(ServerRole.STANDALONE);
        manager.register(new RecordingModule("whitelist").registerEverything());

        manager.reconcile(desire("whitelist"));
        manager.reconcile(Collections.<String>emptySet());
        manager.reconcile(desire("whitelist"));

        assertEquals(1, loginPipeline.size(),
                "a hot toggle that leaves a stale interceptor behind would run the login check twice");
        assertEquals(1, chatPipeline.observerCount());
    }

    @Test
    @DisplayName("a module registering through a stale context has it undone, loudly")
    void aStaleContextCannotLeak() {
        ModuleManager manager = manager(ServerRole.STANDALONE);
        RecordingModule module = new RecordingModule("whitelist").registerEverything();
        manager.register(module);
        manager.reconcile(desire("whitelist"));
        ModuleContext stale = module.lastContext();
        manager.reconcile(Collections.<String>emptySet());

        stale.interceptLogin(attempt -> null, 5);

        assertEquals(0, loginPipeline.size());
        assertTrue(logger.logged(LogLevel.WARN, "registered something after it was disabled"));
    }

    // ── Failure containment ──────────────────────────────────────────────────

    @Test
    @DisplayName("a module that throws on enable is unwound, marked failed, and does not stop the rest")
    void aFailingModuleIsContained() {
        ModuleManager manager = manager(ServerRole.STANDALONE);
        RecordingModule broken = new RecordingModule("broken").registerEverything().failOnEnable();
        RecordingModule healthy = new RecordingModule("healthy");
        manager.register(broken);
        manager.register(healthy);

        manager.reconcile(desire("broken", "healthy"));

        assertEquals(ModuleState.FAILED, manager.state("broken"));
        assertEquals(ModuleState.ENABLED, manager.state("healthy"));
        assertEquals(0, loginPipeline.size(),
                "the half-registered interceptor from the failed enable must not survive");
        assertTrue(logger.logged(LogLevel.SEVERE, "failed to start"));
    }

    @Test
    @DisplayName("a failed module is not retried on every push, but toggling it off and on retries it")
    void failedModulesAreNotRetriedUntilToggled() {
        ModuleManager manager = manager(ServerRole.STANDALONE);
        RecordingModule broken = new RecordingModule("broken").failOnEnable();
        manager.register(broken);

        manager.reconcile(desire("broken"));
        manager.reconcile(desire("broken"));
        manager.reconcile(desire("broken"));
        assertEquals(1, broken.enableCalls(),
                "retrying on every config push turns one severe line into a flood that buries it");

        manager.reconcile(Collections.<String>emptySet());
        assertEquals(ModuleState.STOPPED, manager.state("broken"));
        manager.reconcile(desire("broken"));
        assertEquals(2, broken.enableCalls(),
                "toggling off and on is what an operator does after fixing the cause");
    }

    @Test
    @DisplayName("a module that throws on disable still has its registrations unwound")
    void aFailingDisableStillUnwinds() {
        ModuleManager manager = manager(ServerRole.STANDALONE);
        manager.register(new RecordingModule("whitelist").registerEverything().failOnDisable());

        manager.reconcile(desire("whitelist"));
        manager.reconcile(Collections.<String>emptySet());

        assertEquals(0, loginPipeline.size(),
                "tracking registrations exists precisely so a module's own teardown cannot be "
                        + "trusted with them");
        assertTrue(logger.logged(LogLevel.SEVERE, "threw while stopping"));
    }

    @Test
    @DisplayName("(S8) one registration whose close() throws does not leak the ones behind it")
    void aThrowingCloseDoesNotStopTheUnwind() {
        ModuleManager manager = manager(ServerRole.STANDALONE);
        manager.register(new HeimdallModule() {
            @Override
            public String id() {
                return "messy";
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
            public void enable(ModuleContext context) {
                // Registered first, so it is unwound LAST — the handle behind the exploding one.
                context.interceptLogin(attempt -> null, 10);
                context.observeChat(message -> {
                });
            }

            @Override
            public void disable() {
            }
        });
        manager.reconcile(desire("messy"));
        assertEquals(1, loginPipeline.size());

        manager.shutdown();

        assertEquals(0, loginPipeline.size());
        assertEquals(0, chatPipeline.observerCount(),
                "handles are unwound newest-first, so anything registered early is exactly what a "
                        + "failure partway through would strand");
    }

    @Test
    @DisplayName("(S8) a module that explodes on shutdown does not strand the modules after it")
    void oneBadModuleDoesNotStrandTheRest() {
        ModuleManager manager = manager(ServerRole.STANDALONE);
        RecordingModule first = new RecordingModule("first").registerEverything().failOnDisable();
        RecordingModule second = new RecordingModule("second").registerEverything();
        manager.register(first);
        manager.register(second);
        manager.reconcile(desire("first", "second"));
        assertEquals(2, loginPipeline.size());

        manager.shutdown();

        assertEquals(ModuleState.STOPPED, manager.state("first"));
        assertEquals(ModuleState.STOPPED, manager.state("second"));
        assertEquals(0, loginPipeline.size(),
                "shutdown stops in reverse registration order, so a throw from the LAST one to be "
                        + "stopped must not leave the plugin believing it has shut down while a "
                        + "module is still gating logins");
    }

    // ── Role eligibility ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a module excluded by role never enables, whatever the dashboard says")
    void roleEligibilityOutranksConfiguration() {
        ModuleManager manager = manager(ServerRole.ENFORCER);
        RecordingModule gatekeeperOnly = new RecordingModule("whitelist",
                RecordingModule.caps(Capabilities.WHITELIST),
                RecordingModule.roles(ServerRole.STANDALONE, ServerRole.GATEKEEPER));
        manager.register(gatekeeperOnly);

        manager.reconcile(desire("whitelist"));

        assertEquals(ModuleState.INELIGIBLE, manager.state("whitelist"));
        assertEquals(0, gatekeeperOnly.enableCalls(),
                "two components both enforcing the same login is the failure the role system exists "
                        + "to prevent");
        assertTrue(logger.logged(LogLevel.INFO, "does not run on a enforcer server"));
    }

    @Test
    @DisplayName("an empty role set means any role")
    void anEmptyRoleSetMeansAnyRole() {
        ModuleManager manager = manager(ServerRole.ENFORCER);
        manager.register(new RecordingModule("rolesync"));

        manager.reconcile(desire("rolesync"));

        assertEquals(ModuleState.ENABLED, manager.state("rolesync"));
    }

    // ── Capabilities ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the declared capability set is the union over ENABLED modules only")
    void capabilitiesAggregateOverEnabledModules() {
        ModuleManager manager = manager(ServerRole.STANDALONE);
        manager.register(new RecordingModule("whitelist",
                RecordingModule.caps(Capabilities.WHITELIST, Capabilities.CONFIG),
                Collections.<ServerRole>emptySet()));
        manager.register(new RecordingModule("console",
                RecordingModule.caps(Capabilities.CONSOLE), Collections.<ServerRole>emptySet()));

        assertEquals(Collections.emptySet(), manager.capabilities());

        manager.reconcile(desire("whitelist"));
        assertEquals(desire(Capabilities.WHITELIST, Capabilities.CONFIG), manager.capabilities());

        manager.reconcile(desire("whitelist", "console"));
        assertEquals(desire(Capabilities.WHITELIST, Capabilities.CONFIG, Capabilities.CONSOLE),
                manager.capabilities());

        manager.reconcile(Collections.<String>emptySet());
        assertEquals(Collections.emptySet(), manager.capabilities(),
                "claiming a capability for a switched-off module means receiving settings nothing "
                        + "will read");
    }

    // ── Config-driven hot toggle ─────────────────────────────────────────────

    @Test
    @DisplayName("a dashboard toggle enables and disables the module live")
    void configChangesDriveTheLifecycle() {
        ModuleManager manager = manager(ServerRole.STANDALONE);
        RecordingModule whitelist = new RecordingModule("whitelist").registerEverything();
        manager.register(whitelist);
        manager.followRemoteConfig();
        remoteConfig.onModeChanged(ProtocolMode.UNKNOWN, ProtocolMode.V3);

        remoteConfig.onConfigPush(document(1, "whitelist", true));
        assertEquals(ModuleState.ENABLED, manager.state("whitelist"));
        assertEquals(1, loginPipeline.size());

        remoteConfig.onConfigPush(document(2, "whitelist", false));
        assertEquals(ModuleState.STOPPED, manager.state("whitelist"));
        assertEquals(0, loginPipeline.size());
    }

    @Test
    @DisplayName("a module reads its settings live, not as captured at enable time")
    void settingsAreReadLive() {
        ModuleManager manager = manager(ServerRole.STANDALONE);
        RecordingModule whitelist = new RecordingModule("whitelist");
        manager.register(whitelist);
        manager.followRemoteConfig();
        remoteConfig.onModeChanged(ProtocolMode.UNKNOWN, ProtocolMode.V3);
        remoteConfig.onConfigPush(settingsDocument(1, 60));

        assertEquals(60, whitelist.settingsNow().intValue("window-minutes", -1));

        remoteConfig.onConfigPush(settingsDocument(2, 15));

        assertEquals(15, whitelist.settingsNow().intValue("window-minutes", -1),
                "a settings change does not re-enable the module, so a field cached in enable() "
                        + "would be permanently stale after the first dashboard edit");
        assertEquals(1, whitelist.enableCalls());
    }

    // ── Registration ─────────────────────────────────────────────────────────

    @Test
    void duplicateIdsAreRejected() {
        ModuleManager manager = manager(ServerRole.STANDALONE);
        manager.register(new RecordingModule("whitelist"));

        assertThrows(IllegalStateException.class,
                () -> manager.register(new RecordingModule("whitelist")));
    }

    @Test
    void shutdownStopsEverything() {
        ModuleManager manager = manager(ServerRole.STANDALONE);
        RecordingModule whitelist = new RecordingModule("whitelist").registerEverything();
        manager.register(whitelist);
        manager.reconcile(desire("whitelist"));

        manager.shutdown();

        assertEquals(ModuleState.STOPPED, manager.state("whitelist"));
        assertEquals(1, whitelist.disableCalls());
        assertEquals(0, loginPipeline.size());
        assertEquals(Collections.emptySet(), manager.capabilities());
    }

    @Test
    @DisplayName("a module with no tunnel gets a disconnected one rather than a null")
    void modulesWithoutATunnelSeeADisconnectedBus() {
        ModuleManager manager = manager(ServerRole.STANDALONE);
        RecordingModule module = new RecordingModule("whitelist").registerEverything();
        manager.register(module);
        manager.reconcile(desire("whitelist"));

        assertFalse(module.lastContext().tunnel().isConnected());
        assertEquals(ProtocolMode.UNKNOWN, module.lastContext().tunnel().mode());
        assertTrue(module.lastContext().tunnel()
                .sendAndWait("get_players", Payload.empty(), 10L).isCompletedExceptionally(),
                "behaving like a disconnected tunnel means a module needs no separate code path for "
                        + "an unconfigured server");
    }

    @Test
    @DisplayName("the module logger names the module")
    void moduleLoggersArePrefixed() {
        ModuleManager manager = manager(ServerRole.STANDALONE);
        RecordingModule module = new RecordingModule("whitelist");
        manager.register(module);
        manager.reconcile(desire("whitelist"));

        module.lastContext().logger().warn("could not reach the bot");

        assertTrue(logger.logged(LogLevel.WARN, "[whitelist] could not reach the bot"),
                "an operator reading 'could not reach the bot' needs to know whether players are "
                        + "being kept out right now or a punishment will be retried later");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Payload document(int version, String moduleId, boolean enabled) {
        return Payload.builder()
                .put("version", version)
                .put("modules", Payload.builder()
                        .put(moduleId, Payload.builder().put("enabled", enabled).build())
                        .build())
                .build();
    }

    private static Payload settingsDocument(int version, int windowMinutes) {
        return Payload.builder()
                .put("version", version)
                .put("modules", Payload.builder()
                        .put("whitelist", Payload.builder()
                                .put("enabled", true)
                                .put("settings",
                                        Payload.builder().put("window-minutes", windowMinutes).build())
                                .build())
                        .build())
                .build();
    }

    /** Records enable/disable order into a shared list. */
    private static final class OrderedModule implements HeimdallModule {

        private final String id;
        private final List<String> order;

        OrderedModule(String id, List<String> order) {
            this.id = id;
            this.order = order;
        }

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
        public void enable(ModuleContext context) {
            order.add("enable:" + id);
        }

        @Override
        public void disable() {
            order.add("disable:" + id);
        }
    }
}
