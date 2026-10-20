package com.heimdall.module.rolesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.http.model.RoleSyncDirective;
import com.heimdall.core.json.Payload;
import com.heimdall.core.testing.FakeLuckPerms;
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
 * The reported case, end to end on the plugin side: Pio (guild Meraki, standalone) holds
 * {@code zeal}, mapped {@code both}.
 *
 * <ol>
 *   <li>Pio joins unlinked holding zeal, links, and the bot's {@code rolesync.resync} gets a frame
 *       that grants the role. Before, nothing told the bot about a rank he already had.
 *   <li>The tunnel reconnects, zeal is removed in game, and a frame removes the role. Before, the
 *       reconnect left him unrecorded and the removal compared {@code {}} with {@code {}}. A join
 *       call that started before the removal and answers after it does not overwrite the frame's
 *       record (s4).
 *   <li>A rejoin, through a stamped join call and its applied directive, does not put zeal back,
 *       in game or in a frame.
 * </ol>
 */
class RoleSyncPioSequenceTest {

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
    @DisplayName("Pio: link and resync grant, a removal after a reconnect removes, a rejoin does not re-add")
    void piosSequence() {
        harness.configure(RoleSyncHarness.watching("zeal"));
        FakePlayer pio = harness.online("Pio");
        harness.luckPerms.holding(pio.uuid(), "default", "zeal");
        harness.sessions.join(pio, 1L);
        // The join call carried {default, zeal}, but Pio was not linked, so the bot could do
        // nothing with it; the record still says it was told.
        harness.module.groupsDelivered(pio.uuid(), Arrays.asList("default", "zeal"));
        sleep(PAST_WINDOW_MS);
        assertEquals(0, frames().size());

        // He links; the bot asks.
        harness.bus.push(GroupChangeReporter.RESYNC_FRAME_TYPE,
                Payload.builder().put("uuid", pio.uuid().toString()).put("username", "Pio").build());
        assertEquals(1, frames().size(), "answered although the record already agreed");
        assertEquals(Arrays.asList("default", "zeal"), frames().get(0).payload().strings("groups"));

        // A reconnect drops every record.
        harness.bus.disconnected();
        harness.bus.reconnected();

        // A join-time call starts while he still holds zeal (a server switch on the network, say).
        long inFlight = harness.module.groupsRequestStarting(pio.uuid());

        // zeal is removed in game.
        harness.luckPerms.fireInheritanceChanged(pio.uuid(), "pio", Arrays.asList("default"));
        await(() -> frames().size() == 2, "the removal is reported: " + harness.logger.records());
        Payload removal = frames().get(1).payload();
        assertEquals(Arrays.asList("default"), removal.strings("groups"));
        assertEquals("Pio", removal.string("username", null), "the online name wins");

        // s4: the in-flight call's answer lands after the frame, carrying the list it read before
        // the removal. It must not become the record: the frame told the bot {default} since.
        harness.module.groupsDelivered(pio.uuid(), Arrays.asList("default", "zeal"), inFlight);
        harness.luckPerms.fireGroupsChanged(pio.uuid(), Arrays.asList("default"));
        sleep(PAST_WINDOW_MS);
        assertEquals(2, frames().size(),
                "recorded as {zeal}, an unchanged {default} would be sent again as a removal");

        // He rejoins, holding no zeal. The join call is stamped when it starts, carries {default},
        // and the bot's answer (s7: an in-game removal of a both rank wins at join) drops zeal.
        harness.platform.leave(pio);
        harness.sessions.quit(pio, 2L);
        FakePlayer back = harness.online("Pio");
        harness.luckPerms.holding(back.uuid(), "default");
        long asked = harness.module.groupsRequestStarting(back.uuid());
        harness.sessions.join(back, 3L);
        harness.module.groupsDelivered(back.uuid(), Arrays.asList("default"), asked);
        harness.module.applyOnJoin(back.uuid(), "Pio",
                RoleSyncDirective.enabled(Arrays.<String>asList(), Arrays.asList("zeal")), asked);

        FakeLuckPerms.Sync rejoin = harness.luckPerms.lastSync();
        assertNotNull(rejoin, "the join answer was applied: " + harness.logger.records());
        assertEquals(back.uuid(), rejoin.uuid());
        assertFalse(rejoin.targetGroups().contains("zeal"), "the rejoin does not re-add zeal");
        assertEquals(Arrays.asList("zeal"), rejoin.managedGroups());

        harness.luckPerms.fireGroupsChanged(back.uuid(), Arrays.asList("default", "builder"));

        sleep(PAST_WINDOW_MS);
        assertEquals(2, frames().size(), "nothing new to tell the bot");
        for (RecordingTunnelBus.Sent sent : frames().subList(1, frames().size())) {
            assertFalse(sent.payload().strings("groups").contains("zeal"));
        }
    }
}
