package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.RecordingTunnelBus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bot's alt-list question, {@code dupeip.query} in and {@code dupeip_result} out.
 *
 * <p>Two properties carry most of the weight here. The first is that <strong>every</strong> role
 * answers: an enforcer holds no last-address file and has nothing to contribute, and if it stayed
 * quiet the bot would spend its whole request timeout learning that. The second is that no address
 * ever appears in the reply - the raw addresses are the reason the file is local, and the assertion
 * below is written against the serialised JSON rather than against named keys so a field added
 * later cannot smuggle one out under a name this test never thought to check.
 *
 * <p>The in-game {@code /dupeip} is pinned here too, next to the tunnel reply rather than off with
 * the other command tests, because the property worth protecting is not either answer on its own -
 * it is that the two agree. They are one method apart ({@link LastIpStore#altsOf}) and that is the
 * only reason they cannot drift; a test that watched one of them would not notice the day somebody
 * gave the other its own matching.
 */
class DupeipQueryTest {

    private static final String STEVE = "11111111-1111-1111-1111-111111111111";
    private static final String ALEX = "22222222-2222-2222-2222-222222222222";
    private static final String HEROBRINE = "33333333-3333-3333-3333-333333333333";
    private static final String SHARED_IP = "203.0.113.9";

    @TempDir
    Path dataDir;

    @Test
    @DisplayName("a gatekeeper answers with the accounts sharing the target's last address")
    void gatekeeperRepliesWithAlts() {
        try (PunishmentsHarness harness = gatekeeper()) {
            LastIpStore ips = harness.module.lastIpsForTest();
            ips.record(STEVE, "Steve", SHARED_IP, 1_000L);
            ips.record(ALEX, "Alex", SHARED_IP, 2_000L);
            ips.record(HEROBRINE, "Herobrine", SHARED_IP, 3_000L);

            Payload reply = ask(harness, "req-1", STEVE);

            assertEquals(STEVE, reply.string("uuid", ""), "the reply echoes the uuid asked about");
            List<Payload> alts = reply.children("alts");
            assertEquals(2, alts.size(), reply.toJson());
            assertEquals(ALEX, alts.get(0).string("uuid", ""));
            assertEquals("Alex", alts.get(0).string("name", ""));
            assertEquals(2_000L, alts.get(0).longValue("lastSeenAt", 0L));
            assertEquals(HEROBRINE, alts.get(1).string("uuid", ""));
            assertEquals("Herobrine", alts.get(1).string("name", ""));
            assertEquals(3_000L, alts.get(1).longValue("lastSeenAt", 0L));

            // The wire contract, spelled out. The bot correlates on the envelope id and reads these
            // exact keys; pinning the serialised form means a reordering or a renamed field is a
            // failing test here rather than an empty alt list on a dashboard nobody is watching.
            assertEquals("{\"uuid\":\"" + STEVE + "\",\"alts\":["
                            + "{\"uuid\":\"" + ALEX + "\",\"name\":\"Alex\",\"lastSeenAt\":2000},"
                            + "{\"uuid\":\"" + HEROBRINE + "\",\"name\":\"Herobrine\",\"lastSeenAt\":3000}"
                            + "]}",
                    reply.toJson());
        }
    }

    @Test
    @DisplayName("the target is never listed as its own alt")
    void targetIsExcluded() {
        try (PunishmentsHarness harness = gatekeeper()) {
            LastIpStore ips = harness.module.lastIpsForTest();
            ips.record(STEVE, "Steve", SHARED_IP, 1_000L);
            ips.record(ALEX, "Alex", SHARED_IP, 2_000L);

            Payload reply = ask(harness, "req-2", STEVE);

            List<String> uuids = uuidsOf(reply);
            assertEquals(1, uuids.size(), reply.toJson());
            assertFalse(uuids.contains(STEVE.toLowerCase(Locale.ROOT)),
                    "an account is not its own alt: " + reply.toJson());
            assertEquals(ALEX, uuids.get(0));
        }
    }

    @Test
    @DisplayName("an enforcer still replies, with an empty list rather than silence")
    void enforcerRepliesEmpty() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir.resolve("enf"), ServerRole.ENFORCER)
                .enableReplace()) {
            assertEquals(1, harness.tunnel.subscriberCount(HeimdallPunishmentsModule.DUPEIP_QUERY),
                    "every role subscribes; only the answer differs");

            Payload reply = ask(harness, "req-3", STEVE);

            assertEquals(STEVE, reply.string("uuid", ""));
            assertTrue(reply.hasArray("alts"), "alts is present and an array: " + reply.toJson());
            assertEquals(0, reply.children("alts").size(), reply.toJson());
        }
    }

    @Test
    @DisplayName("a uuid the store has never seen is an empty list, not a missing reply")
    void unknownUuidRepliesEmpty() {
        try (PunishmentsHarness harness = gatekeeper()) {
            harness.module.lastIpsForTest().record(ALEX, "Alex", SHARED_IP, 2_000L);

            Payload reply = ask(harness, "req-4", HEROBRINE);

            assertEquals(HEROBRINE, reply.string("uuid", ""));
            assertEquals(0, reply.children("alts").size(), reply.toJson());
        }
    }

    @Test
    @DisplayName("no address, prefix, digest or CIDR reaches the wire")
    void replyCarriesNothingAboutTheAddress() {
        try (PunishmentsHarness harness = gatekeeper()) {
            LastIpStore ips = harness.module.lastIpsForTest();
            ips.record(STEVE, "Steve", SHARED_IP, 1_000L);
            ips.record(ALEX, "Alex", SHARED_IP, 2_000L);
            ips.record(ALEX, "Alex", "198.51.100.4", 2_500L);
            ips.record(STEVE, "Steve", "198.51.100.4", 3_000L);

            Payload reply = ask(harness, "req-5", STEVE);

            String json = reply.toJson();
            assertEquals(1, reply.children("alts").size(), json);
            assertFalse(json.contains("198.51.100"), json);
            assertFalse(json.contains("203.0.113"), json);
            assertFalse(json.contains("198.51."), json);
            assertFalse(json.contains("\"ip\""), json);
            assertFalse(json.contains("Digest"), json);
            assertFalse(json.contains("cidr"), json);
            // Every key on every row, named. A future field carrying an address has to get past
            // this list first.
            for (Payload row : reply.children("alts")) {
                assertEquals("[uuid, name, lastSeenAt]", row.keys().toString(), json);
            }
            assertEquals("[uuid, alts]", reply.keys().toString(), json);
        }
    }

    @Test
    @DisplayName("a name the store has never learned is omitted rather than sent empty")
    void unknownNameIsOmitted() {
        try (PunishmentsHarness harness = gatekeeper()) {
            LastIpStore ips = harness.module.lastIpsForTest();
            ips.record(STEVE, "Steve", SHARED_IP, 1_000L);
            ips.record(ALEX, null, SHARED_IP, 2_000L);

            Payload reply = ask(harness, "req-6", STEVE);

            List<Payload> alts = reply.children("alts");
            assertEquals(1, alts.size(), reply.toJson());
            assertEquals(ALEX, alts.get(0).string("uuid", ""));
            assertFalse(alts.get(0).has("name"), "an unknown name is absent, not \"\": " + reply.toJson());
            assertEquals(2_000L, alts.get(0).longValue("lastSeenAt", 0L));
        }
    }

    @Test
    @DisplayName("/dupeip agrees with the tunnel: the target is not one of its own alts")
    void theCommandExcludesTheTargetTheSameWay() {
        try (PunishmentsHarness harness = gatekeeper()) {
            LastIpStore ips = harness.module.lastIpsForTest();
            ips.record(STEVE, "Steve", SHARED_IP, 1_000L);
            ips.record(ALEX, "Alex", SHARED_IP, 2_000L);

            FakeCommandSource console = FakeCommandSource.console();
            harness.module.onStaffCommand(console, "dupeip", Collections.singletonList("Steve"));

            List<String> told = console.messageText();
            assertEquals(2, told.size(), told.toString());
            // The header names the player asked about, so "Steve" belongs there. The list below it
            // is the answer, and Steve is not an alt of Steve.
            assertTrue(told.get(0).contains("(1)"),
                    "the count drops the target as well as the list: " + told);
            assertTrue(told.get(1).contains("Alex"), told.toString());
            assertFalse(told.get(1).contains("Steve"),
                    "an account is not its own alt: " + told);

            assertEquals(uuidsOf(ask(harness, "req-7", STEVE)).size(), 1,
                    "and the tunnel says the same thing");
        }
    }

    private PunishmentsHarness gatekeeper() {
        return new PunishmentsHarness(dataDir.resolve("gk"), ServerRole.GATEKEEPER).enableReplace();
    }

    /**
     * Pushes one query and returns the reply payload, having first checked it came back on the
     * request's own envelope id. The bot holds a future keyed on that id, so a reply with a fresh
     * one is filed as unsolicited and the request times out anyway.
     */
    private static Payload ask(PunishmentsHarness harness, String requestId, String uuid) {
        harness.tunnel.clearSent();
        int delivered = harness.tunnel.push(Envelope.of(
                requestId,
                HeimdallPunishmentsModule.DUPEIP_QUERY,
                Payload.builder().put("uuid", uuid).build()));
        assertEquals(1, delivered, "nothing was subscribed to " + HeimdallPunishmentsModule.DUPEIP_QUERY);

        List<RecordingTunnelBus.Sent> replies =
                harness.tunnel.sent(HeimdallPunishmentsModule.DUPEIP_RESULT);
        assertEquals(1, replies.size(), "exactly one reply: " + harness.tunnel.sent());
        assertEquals(requestId, replies.get(0).requestId(), "the reply is correlated by envelope id");
        return replies.get(0).payload();
    }

    private static List<String> uuidsOf(Payload reply) {
        List<String> uuids = new ArrayList<String>();
        for (Payload row : reply.children("alts")) {
            uuids.add(row.string("uuid", ""));
        }
        return uuids;
    }
}
