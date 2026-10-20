package com.heimdall.module.rolesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.json.Payload;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.testing.RecordingTunnelBus;
import com.heimdall.core.tunnel.Capabilities;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code rolesync.resync}: the bot asking for a player's groups now, answered with an unconditional
 * {@code rolesync.groups} frame when the player is online here and LuckPerms is available.
 *
 * <p>The wire contract is shared with the bot: capability {@code rolesyncresync@1}, frame
 * {@code rolesync.resync} with {@code {uuid, username?}}, answered by the existing
 * {@code rolesync.groups} shape. The names are asserted as literals so a rename here cannot pass by
 * agreeing with itself.
 */
class RoleSyncResyncTest {

    private static final long PAST_WINDOW_MS = GroupChangeReporter.DEBOUNCE_MS + 400L;

    @TempDir
    Path dataDirectory;

    private RoleSyncHarness harness;

    @BeforeEach
    void setUp() {
        harness = new RoleSyncHarness(dataDirectory);
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private FakePlayer joined(String name, String... groups) {
        FakePlayer player = harness.online(name);
        harness.luckPerms.holding(player.uuid(), groups);
        harness.sessions.join(player, 1L);
        harness.module.groupsDelivered(player.uuid(), Arrays.asList(groups));
        return player;
    }

    private int resync(String uuid, String username) {
        Payload.Builder body = Payload.builder();
        if (uuid != null) {
            body.put("uuid", uuid);
        }
        if (username != null) {
            body.put("username", username);
        }
        return harness.bus.push("rolesync.resync", body.build());
    }

    private List<RecordingTunnelBus.Sent> frames() {
        return harness.bus.sent("rolesync.groups");
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            sleep(20L);
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("the wire names match the bot's contract exactly")
    void wireContract() {
        assertEquals("rolesyncresync@1", Capabilities.ROLE_SYNC_RESYNC);
        assertEquals("rolesync.resync", GroupChangeReporter.RESYNC_FRAME_TYPE);
        assertEquals("rolesync.groups", GroupChangeReporter.FRAME_TYPE);
        assertTrue(harness.module.capabilities().contains("rolesyncresync@1"));
        assertTrue(harness.module.capabilities().contains("rolesync@1"));
    }

    @Test
    @DisplayName("online with LuckPerms: answered with the full list, even when the record already agrees")
    void answeredUnconditionally() {
        harness.configure(RoleSyncHarness.watching("zeal"));
        FakePlayer pio = joined("Pio", "default", "zeal");

        assertEquals(1, resync(pio.uuid().toString(), "Pio"), "subscribed through the context");

        assertEquals(1, frames().size(), "answered at once; no debounce: " + harness.logger.records());
        Payload payload = frames().get(0).payload();
        assertEquals(pio.uuid().toString(), payload.string("uuid", null));
        assertEquals("Pio", payload.string("username", null));
        assertEquals(Arrays.asList("default", "zeal"), payload.strings("groups"),
                "the full list, read from LuckPerms now");
        assertTrue(payload.longValue("at", 0L) > 0L);
    }

    @Test
    @DisplayName("the answer is written as the record, so an unrelated change afterwards sends nothing")
    void answerWritesTheRecord() {
        harness.configure(RoleSyncHarness.watching("zeal"));
        FakePlayer pio = harness.online("Pio");
        harness.luckPerms.holding(pio.uuid(), "default", "zeal");
        harness.sessions.join(pio, 1L);
        // No delivery: unrecorded, so without the resync's record any change would send.

        resync(pio.uuid().toString(), null);
        assertEquals(1, frames().size());

        harness.luckPerms.fireGroupsChanged(pio.uuid(), Arrays.asList("default", "zeal", "builder"));
        sleep(PAST_WINDOW_MS);
        assertEquals(1, frames().size(), "the record now says the bot knows about zeal");

        harness.luckPerms.fireGroupsChanged(pio.uuid(), Arrays.asList("default", "builder"));
        await(() -> frames().size() == 2, "a later removal is still reported: "
                + harness.logger.records());
    }

    @Test
    @DisplayName("answered whatever is watched: the bot is the one that knows which mappings it wants")
    void answeredWithNothingWatched() {
        harness.configure(RoleSyncHarness.watching());
        FakePlayer pio = joined("Pio", "default", "zeal");

        resync(pio.uuid().toString(), "Pio");

        assertEquals(1, frames().size());
    }

    @Test
    @DisplayName("a player who is not online here is ignored")
    void offlineIgnored() {
        harness.configure(RoleSyncHarness.watching("zeal"));
        harness.luckPerms.holding(RoleSyncHarness.uuidOf("Pio"), "default", "zeal");

        resync(RoleSyncHarness.uuidOf("Pio").toString(), "Pio");

        sleep(100L);
        assertEquals(0, frames().size(), "a frame from a server the player is not on could describe "
                + "somebody else's LuckPerms");
    }

    @Test
    @DisplayName("no LuckPerms: ignored, and nothing is warned")
    void noLuckPermsIgnored() {
        harness.configure(RoleSyncHarness.watching("zeal"));
        FakePlayer pio = joined("Pio", "default", "zeal");
        harness.luckPerms.unavailable();

        resync(pio.uuid().toString(), "Pio");

        assertEquals(0, frames().size());
        assertEquals(0, harness.logger.messagesAt(com.heimdall.core.log.LogLevel.WARN).size(),
                harness.logger.records().toString());
    }

    @Test
    @DisplayName("a LuckPerms read that fails is unknown, not empty: nothing is sent")
    void failedReadIgnored() {
        harness.configure(RoleSyncHarness.watching("zeal"));
        FakePlayer pio = joined("Pio", "default", "zeal");
        harness.luckPerms.failing(new IllegalStateException("storage down"));

        resync(pio.uuid().toString(), "Pio");

        assertEquals(0, frames().size(), "sending [] would read as every mapped rank gone");
    }

    @Test
    @DisplayName("tunnel down: nothing is sent")
    void tunnelDownIgnored() {
        harness.configure(RoleSyncHarness.watching("zeal"));
        FakePlayer pio = joined("Pio", "default", "zeal");
        harness.bus.disconnected();

        resync(pio.uuid().toString(), "Pio");

        assertEquals(0, frames().size());
    }

    @Test
    @DisplayName("a missing or unparseable uuid falls back to the username, against online players")
    void usernameFallback() {
        harness.configure(RoleSyncHarness.watching("zeal"));
        FakePlayer pio = joined("Pio", "default", "zeal");

        resync("not-a-uuid", "Pio");
        resync(null, "Pio");
        resync(null, null);

        assertEquals(2, frames().size(), harness.logger.records().toString());
        assertEquals(pio.uuid().toString(), frames().get(0).payload().string("uuid", null));
    }

    @Test
    @DisplayName("disabling the module unsubscribes the resync handler with everything else")
    void unwoundOnDisable() {
        harness.configure(RoleSyncHarness.watching("zeal"));
        assertEquals(1, harness.bus.subscriberCount("rolesync.resync"));
        harness.disable();
        assertEquals(0, harness.bus.subscriberCount("rolesync.resync"));
    }
}
