package com.heimdall.module.whitelist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The module's half of {@code whitelist_changed}: subscribed while enabled, gone while not, and
 * actually reaching the bot.
 *
 * <p>The debounce itself is {@link WhitelistChangeNudgeTest}'s. What is proved here is the wiring
 * around it — including the one thing a unit test of the debouncer cannot show, which is that the
 * scheduled work really is a whitelist sync against the bot rather than something that merely runs.
 */
class WhitelistNudgeTest {

    private static final String FRAME = "whitelist_changed";

    @Test
    @DisplayName("an enabled module subscribes to the notification")
    void anEnabledModuleSubscribes(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(Payload.empty());

            assertEquals(1, h.tunnel.subscriberCount(FRAME),
                    "with nothing subscribed the frame is written off with a debug line and a "
                            + "revoked player keeps playing until the next pre-warm poll");
        }
    }

    @Test
    @DisplayName("a disabled module ignores the frame silently — no handler, no reply")
    void aDisabledModuleIgnoresTheFrame(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(Payload.empty());
            h.awaitMirrorEntries(2);
            h.disableModule();
            h.tunnel.clearSent();

            assertEquals(0, h.tunnel.subscriberCount(FRAME),
                    "the subscription is made through ModuleContext, so a toggle has to unwind it");
            assertEquals(0, h.tunnel.push(Envelope.of("nanoid-1", FRAME, Payload.empty())),
                    "there is no mirror to refresh and no login decision being made, so the right "
                            + "answer is to do nothing at all");
            assertEquals(0, h.tunnel.sent().size(),
                    "it is a notification: the bot has no future waiting, so any reply — an error "
                            + "one included — is noise it would have nowhere to put");
        }
    }

    @Test
    @DisplayName("a notification makes the module actually re-sync its mirror")
    void aNotificationDrivesARealSync(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(Payload.empty());
            assertTrue(h.awaitMirrorEntries(2).startsWith("2 entries"));

            // Emptied along with its ETag, so the next sync is a full one and repopulating the
            // mirror can only have come from a real round trip to the bot. The pre-warm poll is on
            // a five-minute interval and has already run, so nothing else will do it inside the
            // window this test waits.
            h.module.clear();
            assertTrue(h.module.mirrorStats().startsWith("0 entries"), h.module.mirrorStats());

            h.tunnel.push(Envelope.of("nanoid-2", FRAME, Payload.empty()));

            assertTrue(h.awaitMirrorEntries(2).startsWith("2 entries"),
                    "the notification has to turn into a pull — the bot is the source of truth, so "
                            + "the frame carries no diff and the plugin asks: " + h.module.mirrorStats());
        }
    }

    @Test
    @DisplayName("a burst of notifications is answered without wedging anything")
    void aBurstIsSurvivable(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(Payload.empty());
            h.awaitMirrorEntries(2);
            h.module.clear();

            // What a bulk import looks like on the wire. The count that matters is asserted in
            // WhitelistChangeNudgeTest; what this adds is that the real module, on the real
            // scheduler, still ends up with a correct mirror afterwards.
            for (int i = 0; i < 25; i++) {
                h.tunnel.push(Envelope.of("nanoid-burst-" + i, FRAME, Payload.empty()));
            }

            assertTrue(h.awaitMirrorEntries(2).startsWith("2 entries"), h.module.mirrorStats());
        }
    }
}
