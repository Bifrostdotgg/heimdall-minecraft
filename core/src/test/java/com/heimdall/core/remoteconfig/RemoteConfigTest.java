package com.heimdall.core.remoteconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.json.Payload;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.tunnel.ProtocolMode;
import com.heimdall.core.util.Registration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Precedence, monotonicity, the disk cache, and who gets told what changed. */
class RemoteConfigTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    @TempDir
    Path dataDir;

    private Path cachePath() {
        return dataDir.resolve("remote-config.json");
    }

    private static ConfigDocument defaults() {
        Map<String, ModuleConfig> modules = new LinkedHashMap<String, ModuleConfig>();
        modules.put("whitelist", ModuleConfig.of(true, Payload.builder().put("window-minutes", 60).build()));
        modules.put("console", ModuleConfig.of(false, Payload.empty()));
        return ConfigDocument.of(ConfigDocument.UNVERSIONED, modules,
                Payload.builder().put("denied", "§cDenied").build());
    }

    private static Payload push(int version, Map<String, ModuleConfig> modules) {
        Payload.Builder modulesPayload = Payload.builder();
        for (Map.Entry<String, ModuleConfig> entry : modules.entrySet()) {
            modulesPayload.put(entry.getKey(), Payload.builder()
                    .put("enabled", entry.getValue().enabled())
                    .put("settings", entry.getValue().settings())
                    .build());
        }
        return Payload.builder().put("version", version).put("modules", modulesPayload.build()).build();
    }

    private static Map<String, ModuleConfig> modules(String id, boolean enabled, Payload settings) {
        Map<String, ModuleConfig> map = new LinkedHashMap<String, ModuleConfig>();
        map.put(id, ModuleConfig.of(enabled, settings));
        return map;
    }

    /** A connected v3 session — the state in which a push is accepted. */
    private RemoteConfig connected() {
        RemoteConfig config = new RemoteConfig(logger, cachePath(), defaults());
        config.onModeChanged(ProtocolMode.UNKNOWN, ProtocolMode.V3);
        return config;
    }

    // ── Precedence ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("with no cache and no push, the built-in defaults are in force")
    void defaultsApplyWhenNothingElseHas() {
        RemoteConfig config = new RemoteConfig(logger, cachePath(), defaults());

        assertTrue(config.moduleEnabled("whitelist"));
        assertFalse(config.moduleEnabled("console"));
        assertEquals(60, config.moduleSettings("whitelist").intValue("window-minutes", 0));
        assertEquals("§cDenied", config.messages().string("denied", null));
    }

    @Test
    @DisplayName("a push overlays the defaults rather than replacing them")
    void pushesOverlayDefaults() {
        RemoteConfig config = connected();

        config.onConfigPush(push(1, modules("whitelist", true,
                Payload.builder().put("window-minutes", 15).build())));

        assertEquals(15, config.moduleSettings("whitelist").intValue("window-minutes", 0));
        assertTrue(config.moduleConfig("console").isPresent(),
                "the bot narrows its push to declared capabilities, so a module it never mentions "
                        + "must keep its built-in default rather than silently vanishing");
        assertEquals("§cDenied", config.messages().string("denied", null),
                "a push that overrides one template must not delete the rest");
    }

    @Test
    @DisplayName("an explicit disable beats a default that says otherwise")
    void anExplicitDisableWins() {
        RemoteConfig config = connected();

        config.onConfigPush(push(1, modules("whitelist", false, Payload.empty())));

        assertFalse(config.moduleEnabled("whitelist"));
        assertEquals(Collections.emptySet(), config.enabledModuleIds());
    }

    // ── Monotonicity ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a replayed or reordered push is ignored, not applied")
    void stalePushesAreIgnored() {
        RemoteConfig config = connected();
        config.onConfigPush(push(5, modules("whitelist", true,
                Payload.builder().put("window-minutes", 15).build())));

        config.onConfigPush(push(4, modules("whitelist", true,
                Payload.builder().put("window-minutes", 999).build())));
        config.onConfigPush(push(5, modules("whitelist", true,
                Payload.builder().put("window-minutes", 888).build())));

        assertEquals(15, config.moduleSettings("whitelist").intValue("window-minutes", 0),
                "pushes are fire-and-forget frames; applying a replayed one silently reverts a "
                        + "setting an operator just changed");
        assertEquals(5, config.version());
        assertTrue(logger.logged(LogLevel.WARN, "stale remote config push"));
    }

    @Test
    @DisplayName("the first push of a NEW connection is authoritative, even at a lower version")
    void theVersionFloorIsScopedToAConnection() {
        RemoteConfig config = connected();
        config.onConfigPush(push(7, modules("whitelist", true,
                Payload.builder().put("window-minutes", 15).build())));

        // A reconnect: the bot's own counter restarted (a recreated guild document does exactly
        // this). Holding the cached version 7 as a floor forever would wedge this server on stale
        // config permanently, fixable only by deleting a file on the box.
        config.onModeChanged(ProtocolMode.V3, ProtocolMode.UNKNOWN);
        config.onModeChanged(ProtocolMode.UNKNOWN, ProtocolMode.V3);
        config.onConfigPush(push(1, modules("whitelist", true,
                Payload.builder().put("window-minutes", 45).build())));

        assertEquals(45, config.moduleSettings("whitelist").intValue("window-minutes", 0));
        assertEquals(1, config.version());
    }

    // ── The disk cache ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a push is cached, and survives a restart with no bot at all")
    void pushesAreCachedAndRestored() {
        RemoteConfig first = connected();
        first.onConfigPush(push(3, modules("whitelist", true,
                Payload.builder().put("window-minutes", 15).build())));
        assertTrue(Files.exists(cachePath()));

        RemoteConfig restarted = new RemoteConfig(logger, cachePath(), defaults());
        restarted.loadFromCache();

        assertEquals(3, restarted.version());
        assertEquals(15, restarted.moduleSettings("whitelist").intValue("window-minutes", 0),
                "a server restarting while the bot is redeploying must come up as it was, not "
                        + "unconfigured");
        assertEquals("§cDenied", restarted.messages().string("denied", null));
    }

    @Test
    @DisplayName("an unreadable cache falls back to the defaults instead of refusing to start")
    void aCorruptCacheFallsBackToDefaults() throws Exception {
        Files.write(cachePath(), "{ this is not json".getBytes("UTF-8"));

        RemoteConfig config = new RemoteConfig(logger, cachePath(), defaults());
        config.loadFromCache();

        assertTrue(config.moduleEnabled("whitelist"));
        assertEquals(ConfigDocument.UNVERSIONED, config.version());
        assertTrue(logger.logged(LogLevel.WARN, "unreadable"));
    }

    // ── Listeners ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a module listener fires only when that module's own section changes")
    void moduleListenersAreSectionScoped() {
        RemoteConfig config = connected();
        final List<String> whitelistChanges = new CopyOnWriteArrayList<String>();
        final List<String> consoleChanges = new CopyOnWriteArrayList<String>();
        config.subscribeModule("whitelist",
                (id, previous, current) -> whitelistChanges.add(current.settings().toJson()));
        config.subscribeModule("console",
                (id, previous, current) -> consoleChanges.add(current.settings().toJson()));

        config.onConfigPush(push(1, modules("whitelist", true,
                Payload.builder().put("window-minutes", 15).build())));

        assertEquals(1, whitelistChanges.size());
        assertTrue(consoleChanges.isEmpty(),
                "a dashboard edit to the whitelist must not wake every other module");
    }

    @Test
    @DisplayName("an identical re-push — which every reconnect produces — tells nobody anything")
    void identicalPushesAreSilent() {
        RemoteConfig config = connected();
        Payload document = push(1, modules("whitelist", true,
                Payload.builder().put("window-minutes", 15).build()));
        config.onConfigPush(document);

        final List<String> changes = new CopyOnWriteArrayList<String>();
        config.subscribeModule("whitelist", (id, previous, current) -> changes.add(id));
        config.subscribeAll((previous, current) -> changes.add("document"));

        config.onModeChanged(ProtocolMode.UNKNOWN, ProtocolMode.V3);
        config.onConfigPush(document);

        assertTrue(changes.isEmpty(),
                "value comparison rather than identity is what makes a listener safe to do expensive "
                        + "work in — identity would report a change on every reconnect");
    }

    @Test
    @DisplayName("a listener reads back the NEW value, not the one it is being told about")
    void theSnapshotIsSwappedBeforeListenersRun() {
        RemoteConfig config = connected();
        final List<Integer> observed = new CopyOnWriteArrayList<Integer>();
        config.subscribeModule("whitelist", (id, previous, current) ->
                observed.add(Integer.valueOf(config.moduleSettings("whitelist").intValue("window-minutes", -1))));

        config.onConfigPush(push(1, modules("whitelist", true,
                Payload.builder().put("window-minutes", 15).build())));

        assertEquals(Collections.singletonList(Integer.valueOf(15)), observed);
    }

    @Test
    @DisplayName("closing a subscription really stops it")
    void subscriptionsCanBeClosed() {
        RemoteConfig config = connected();
        final List<String> changes = new CopyOnWriteArrayList<String>();
        Registration registration =
                config.subscribeModule("whitelist", (id, previous, current) -> changes.add(id));
        registration.close();
        registration.close();

        config.onConfigPush(push(1, modules("whitelist", true,
                Payload.builder().put("window-minutes", 15).build())));

        assertTrue(changes.isEmpty());
    }

    @Test
    @DisplayName("a listener that throws does not stop the others or the push")
    void aThrowingListenerIsContained() {
        RemoteConfig config = connected();
        final List<String> changes = new CopyOnWriteArrayList<String>();
        config.subscribeModule("whitelist", (id, previous, current) -> {
            throw new IllegalStateException("bad listener");
        });
        config.subscribeModule("whitelist", (id, previous, current) -> changes.add("survivor"));

        config.onConfigPush(push(1, modules("whitelist", true,
                Payload.builder().put("window-minutes", 15).build())));

        assertEquals(Collections.singletonList("survivor"), changes);
        assertEquals(15, config.moduleSettings("whitelist").intValue("window-minutes", 0));
    }

    // ── Document semantics ───────────────────────────────────────────────────

    @Test
    @DisplayName("absent is not the same as disabled")
    void absentIsNotDisabled() {
        ConfigDocument document = ConfigDocument.fromPayload(
                push(1, modules("whitelist", false, Payload.empty())));

        assertTrue(document.module("whitelist").isPresent());
        assertFalse(document.module("whitelist").enabled());
        assertFalse(document.module("never-mentioned").isPresent());
        assertFalse(document.module("never-mentioned").enabled());
    }

    @Test
    @DisplayName("a malformed document degrades to empty sections rather than failing")
    void malformedDocumentsDegrade() {
        ConfigDocument document = ConfigDocument.fromPayload(
                Payload.parse("{\"version\":\"not a number\",\"modules\":\"also wrong\"}"));

        assertEquals(ConfigDocument.UNVERSIONED, document.version());
        assertEquals(Collections.emptySet(), new LinkedHashSet<String>(document.moduleIds()));
    }

    @Test
    void documentsRoundTripThroughTheirWireShape() {
        ConfigDocument original = ConfigDocument.fromPayload(push(9, modules("whitelist", true,
                Payload.builder().put("window-minutes", 15).putStrings("groups",
                        Arrays.asList("vip", "member")).build())));

        assertEquals(original, ConfigDocument.fromPayload(original.toPayload()));
        assertEquals(original.hashCode(), ConfigDocument.fromPayload(original.toPayload()).hashCode());
    }
}
