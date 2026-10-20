package com.heimdall.module.rolesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.json.Payload;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.testing.RecordingTunnelBus;
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
 * Reverse role sync: in-game LuckPerms rank changes reported to the bot as {@code rolesync.groups}.
 *
 * <p>Driven through the real module manager and a real remote config, so "a settings change takes
 * effect" is the same config listener production fires, and the debounce runs on the real
 * {@code heimdall-sched} and {@code heimdall-io} pools at its real 500 ms. Every "no frame" assertion
 * waits past the window first, or it would be proving only that the timer had not fired yet.
 */
class RoleSyncReverseTest {

    /** Comfortably past the debounce, so "nothing was sent" means nothing will be. */
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

    /**
     * Puts a player online holding {@code groups}, and reports a successful join call that carried
     * them, as the whitelist does after a {@code connection-attempt} answer.
     */
    private FakePlayer joined(String name, String... groups) {
        FakePlayer player = joinedWithoutDelivery(name, groups);
        harness.module.groupsDelivered(player.uuid(), Arrays.asList(groups));
        return player;
    }

    /** A join no call from this server told the bot about: failed, deferred, or bot unusable. */
    private FakePlayer joinedWithoutDelivery(String name, String... groups) {
        FakePlayer player = harness.online(name);
        harness.luckPerms.holding(player.uuid(), groups);
        harness.sessions.join(player, 1L);
        return player;
    }

    private void change(FakePlayer player, String... groups) {
        harness.luckPerms.fireGroupsChanged(player.uuid(), Arrays.asList(groups));
    }

    private List<RecordingTunnelBus.Sent> frames() {
        return harness.bus.sent(GroupChangeReporter.FRAME_TYPE);
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
    @DisplayName("a watched group added sends one frame carrying the full current group list")
    void watchedGroupAdded() {
        harness.configure(RoleSyncHarness.watching("vip"));
        FakePlayer steve = joined("Steve", "default");

        change(steve, "default", "vip", "builder");

        await(() -> frames().size() == 1, "one rolesync.groups frame: " + harness.logger.records());
        Payload payload = frames().get(0).payload();
        assertEquals(steve.uuid().toString(), payload.string("uuid", null));
        assertEquals("Steve", payload.string("username", null));
        assertEquals(Arrays.asList("default", "vip", "builder"), payload.strings("groups"),
                "the FULL list, not the watched intersection: the bot compares sets and may know "
                        + "mappings this server's settings have not caught up with");
        assertTrue(payload.longValue("at", 0L) > 0L);
        sleep(PAST_WINDOW_MS);
        assertEquals(1, frames().size());
    }

    @Test
    @DisplayName("a change that touches no watched group sends nothing")
    void unwatchedChange() {
        harness.configure(RoleSyncHarness.watching("vip"));
        FakePlayer steve = joined("Steve", "default", "vip");

        change(steve, "default", "vip", "builder");

        sleep(PAST_WINDOW_MS);
        assertEquals(0, frames().size(),
                "the join seeded {vip}, and {vip} is still what they hold of the watched set");
    }

    @Test
    @DisplayName("several changes inside the window collapse into one frame with the final state")
    void burstIsDebounced() {
        harness.configure(RoleSyncHarness.watching("vip", "mvp"));
        FakePlayer steve = joined("Steve", "default", "vip");

        // A rank upgrade as a store runs it: old rank off, new rank on, then LuckPerms recalculates.
        change(steve, "default");
        change(steve, "default", "mvp");
        change(steve, "default", "mvp");

        await(() -> frames().size() == 1, "one frame for the burst: " + harness.logger.records());
        sleep(PAST_WINDOW_MS);
        assertEquals(1, frames().size(), "one purchase, one frame");
        assertEquals(Arrays.asList("default", "mvp"), frames().get(0).payload().strings("groups"),
                "the newest state, not the first one seen");
    }

    @Test
    @DisplayName("a watched group removed is reported, so a refund or an expiry reaches Discord")
    void watchedGroupRemoved() {
        harness.configure(RoleSyncHarness.watching("vip"));
        FakePlayer steve = joined("Steve", "default", "vip");

        change(steve, "default");

        await(() -> frames().size() == 1, "the removal should be reported: " + harness.logger.records());
        assertEquals(Arrays.asList("default"), frames().get(0).payload().strings("groups"));
    }

    @Test
    @DisplayName("a settings change that adds a watched group applies without a re-enable")
    void settingsChangeTakesEffect() {
        harness.configure(RoleSyncHarness.watching());
        FakePlayer steve = joined("Steve", "default");
        assertEquals(0, harness.luckPerms.groupListenerCount(),
                "nothing watched, so nothing subscribed: an unmapped guild pays nothing");

        change(steve, "default", "vip");
        sleep(PAST_WINDOW_MS);
        assertEquals(0, frames().size());

        harness.configure(RoleSyncHarness.watching("vip"));
        assertEquals(1, harness.luckPerms.groupListenerCount(),
                "the config listener subscribed; a settings change does not re-enable the module");
        change(steve, "default", "vip", "builder");
        await(() -> frames().size() == 1, "vip is now watched: " + harness.logger.records());

        harness.configure(RoleSyncHarness.watching("vip", "mvp"));
        change(steve, "default", "vip", "builder", "mvp");
        await(() -> frames().size() == 2, "mvp was added to the list live: " + harness.logger.records());
        assertEquals(Arrays.asList("default", "vip", "builder", "mvp"),
                frames().get(1).payload().strings("groups"));
    }

    @Test
    @DisplayName("disable stops reporting and unsubscribes from LuckPerms; enable again resumes")
    void disableStopsReporting() {
        harness.configure(RoleSyncHarness.watching("vip"));
        FakePlayer steve = joined("Steve", "default");
        assertEquals(1, harness.luckPerms.groupListenerCount());

        change(steve, "default", "vip");
        harness.disable();
        assertEquals(0, harness.luckPerms.groupListenerCount(),
                "a subscription left behind would report for a module the dashboard switched off");

        sleep(PAST_WINDOW_MS);
        assertEquals(0, frames().size(), "the armed report was cancelled with the module");

        change(steve, "default");
        sleep(PAST_WINDOW_MS);
        assertEquals(0, frames().size());

        harness.configure(RoleSyncHarness.watching("vip"));
        assertEquals(1, harness.luckPerms.groupListenerCount(), "exactly one after re-enable, not two");
    }

    @Test
    @DisplayName("nothing is sent while the tunnel is down, and the difference is reported on the next change")
    void tunnelDownSkips() {
        harness.configure(RoleSyncHarness.watching("vip"));
        FakePlayer steve = joined("Steve", "default");
        harness.bus.disconnected();

        change(steve, "default", "vip");
        sleep(PAST_WINDOW_MS);
        assertEquals(0, frames().size());

        harness.bus.reconnected();
        change(steve, "default", "vip", "builder");
        await(() -> frames().size() == 1,
                "the unreported vip is still a difference: " + harness.logger.records());
    }

    @Test
    @DisplayName("a bare recalculation for a player who is not online is not reported")
    void offlineRecalculationIgnored() {
        harness.configure(RoleSyncHarness.watching("vip"));
        // LuckPerms recalculates every user it loads, so this says nothing about a rank edit.
        harness.luckPerms.fireGroupsChanged(RoleSyncHarness.uuidOf("Offline"), Arrays.asList("vip"));

        sleep(PAST_WINDOW_MS);
        assertEquals(0, frames().size());
    }

    @Test
    @DisplayName("a real rank edit for an offline player is reported with the full list and LuckPerms' name")
    void offlineNodeChangeReported() {
        harness.configure(RoleSyncHarness.watching("zeal"));
        java.util.UUID pio = RoleSyncHarness.uuidOf("Pio");

        harness.luckPerms.fireInheritanceChanged(pio, "Pio", Arrays.asList("default"));

        await(() -> frames().size() == 1, "an offline removal must reach the bot: "
                + harness.logger.records());
        Payload payload = frames().get(0).payload();
        assertEquals(pio.toString(), payload.string("uuid", null));
        assertEquals("Pio", payload.string("username", null), "the name LuckPerms stored");
        assertEquals(Arrays.asList("default"), payload.strings("groups"));
        assertTrue(payload.longValue("at", 0L) > 0L);
        sleep(PAST_WINDOW_MS);
        assertEquals(1, frames().size());
    }

    @Test
    @DisplayName("an offline edit and its own recalculation are one frame carrying the newest list")
    void offlineEditAndRecalculationCoalesce() {
        harness.configure(RoleSyncHarness.watching("zeal", "mvp"));
        java.util.UUID pio = RoleSyncHarness.uuidOf("Pio");

        harness.luckPerms.fireInheritanceChanged(pio, "Pio", Arrays.asList("default"));
        harness.luckPerms.fireGroupsChanged(pio, Arrays.asList("default", "mvp"));

        await(() -> frames().size() == 1, "one frame: " + harness.logger.records());
        sleep(PAST_WINDOW_MS);
        assertEquals(1, frames().size());
        assertEquals(Arrays.asList("default", "mvp"), frames().get(0).payload().strings("groups"),
                "a recalculation refreshes an armed offline report rather than being dropped");
    }

    @Test
    @DisplayName("an offline edit for a user LuckPerms has no name for is not reported")
    void offlineNodeChangeWithoutNameSkipped() {
        harness.configure(RoleSyncHarness.watching("zeal"));

        harness.luckPerms.fireInheritanceChanged(
                RoleSyncHarness.uuidOf("Nameless"), null, Arrays.asList("default"));

        sleep(PAST_WINDOW_MS);
        assertEquals(0, frames().size(), "the bot needs a username");
    }

    @Test
    @DisplayName("no record is unknown, not empty: a removal from an unrecorded player is reported")
    void removalWithNoRecordIsReported() {
        harness.configure(RoleSyncHarness.watching("zeal"));
        // Pio joined holding zeal, and no call from this server told the bot.
        FakePlayer pio = joinedWithoutDelivery("Pio", "default", "zeal");

        change(pio, "default");

        await(() -> frames().size() == 1, "comparing {} with {} used to hide this removal: "
                + harness.logger.records());
        assertEquals(Arrays.asList("default"), frames().get(0).payload().strings("groups"));
    }

    @Test
    @DisplayName("an unrecorded player is reported on any change, then compared once recorded")
    void unrecordedPlayerReportedOnceThenCompared() {
        harness.configure(RoleSyncHarness.watching("vip"));
        FakePlayer steve = joinedWithoutDelivery("Steve", "default");

        change(steve, "default", "builder");
        await(() -> frames().size() == 1, "no record, so any change sends: "
                + harness.logger.records());

        change(steve, "default", "builder", "member");
        sleep(PAST_WINDOW_MS);
        assertEquals(1, frames().size(), "the first frame wrote the record; nothing watched moved");
    }

    @Test
    @DisplayName("a quit inside the debounce sends the armed report at once instead of dropping it")
    void quitInsideTheWindowFlushes() {
        harness.configure(RoleSyncHarness.watching("zeal"));
        FakePlayer pio = joined("Pio", "default", "zeal");

        // "Remove the rank, then ban": the kick lands well inside the 500 ms window.
        change(pio, "default");
        harness.platform.leave(pio);
        harness.sessions.quit(pio, 2L);

        assertEquals(1, frames().size(), "flushed synchronously by the quit: "
                + harness.logger.records());
        Payload payload = frames().get(0).payload();
        assertEquals("Pio", payload.string("username", null), "the name they quit with");
        assertEquals(Arrays.asList("default"), payload.strings("groups"));
        sleep(PAST_WINDOW_MS);
        assertEquals(1, frames().size(), "the cancelled timer does not send it a second time");
    }

    @Test
    @DisplayName("a quit with nothing armed sends nothing, and a flushed report still compares")
    void quitFlushStillCompares() {
        harness.configure(RoleSyncHarness.watching("zeal"));
        FakePlayer pio = joined("Pio", "default", "zeal");

        change(pio, "default", "zeal", "builder");
        harness.platform.leave(pio);
        harness.sessions.quit(pio, 2L);

        sleep(PAST_WINDOW_MS);
        assertEquals(0, frames().size(), "zeal was delivered at join and is still held");
    }

    @Test
    @DisplayName("no LuckPerms: the module enables, subscribes to nothing, and warns nothing extra")
    void noLuckPerms() {
        harness.withLuckPerms(null);
        harness.configure(RoleSyncHarness.watching("vip"));
        joined("Steve", "default");

        assertEquals(0, harness.luckPerms.groupListenerCount());
        assertEquals(0, harness.logger.messagesAt(com.heimdall.core.log.LogLevel.WARN).size(),
                "the absence is reported once, on the apply path, not here: "
                        + harness.logger.records());
    }

    @Test
    @DisplayName("no join call delivered the groups: the player is unseeded, so the next change reports")
    void failedJoinCallLeavesThePlayerUnseeded() {
        harness.configure(RoleSyncHarness.watching("vip"));
        // Holding vip at join, but no call from this server told the bot. Seeding from LuckPerms here
        // would suppress the one report that tells it.
        FakePlayer steve = joinedWithoutDelivery("Steve", "default", "vip");

        change(steve, "default", "vip", "builder");

        await(() -> frames().size() == 1, "an unseeded player's watched rank must be reported: "
                + harness.logger.records());
    }

    @Test
    @DisplayName("a delivery that arrives before the join is parked and adopted by the join")
    void deliveryBeforeJoinIsAdopted() {
        harness.configure(RoleSyncHarness.watching("vip"));
        java.util.UUID uuid = RoleSyncHarness.uuidOf("Steve");
        // The blocking login path: the answer arrives while the player is still logging in.
        harness.module.groupsDelivered(uuid, Arrays.asList("default", "vip"));
        FakePlayer steve = joinedWithoutDelivery("Steve", "default", "vip");

        change(steve, "default", "vip", "builder");

        sleep(PAST_WINDOW_MS);
        assertEquals(0, frames().size(), "the bot was told about vip at login");
    }

    @Test
    @DisplayName("a LuckPerms that answers NONE is not treated as subscribed, and the next join retries")
    void refusedSubscriptionIsRetried() {
        harness.luckPerms.refusingListeners(true);
        harness.configure(RoleSyncHarness.watching("vip"));
        assertEquals(0, harness.luckPerms.groupListenerCount());

        harness.luckPerms.refusingListeners(false);
        FakePlayer steve = joined("Steve", "default");
        assertEquals(1, harness.luckPerms.groupListenerCount(),
                "storing NONE as the subscription would have stopped every later retry");

        change(steve, "default", "vip");
        await(() -> frames().size() == 1, "reporting works once subscribed: "
                + harness.logger.records());
    }

    @Test
    @DisplayName("a tunnel reconnect drops every record, so the next change reports again")
    void reconnectDropsRecords() {
        harness.configure(RoleSyncHarness.watching("vip"));
        FakePlayer steve = joined("Steve", "default", "vip");

        harness.bus.disconnected();
        harness.bus.reconnected();
        change(steve, "default", "vip", "builder");

        await(() -> frames().size() == 1, "after a reconnect nothing is assumed delivered: "
                + harness.logger.records());
    }

    @Test
    @DisplayName("disable unwinds the mode listener with everything else")
    void modeListenerIsUnwound() {
        harness.configure(RoleSyncHarness.watching("vip"));
        assertEquals(1, harness.bus.modeListenerCount());
        harness.disable();
        assertEquals(0, harness.bus.modeListenerCount());
    }
}
