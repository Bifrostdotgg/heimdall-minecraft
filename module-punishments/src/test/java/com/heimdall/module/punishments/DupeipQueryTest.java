package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.RecordingTunnelBus;
import com.heimdall.core.tunnel.ProtocolMode;
import com.heimdall.core.tunnel.TunnelBus;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import com.heimdall.core.util.Registration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bot's alt-list question, {@code dupeip.query} in and {@code dupeip_result} out.
 *
 * <p>Three properties carry most of the weight here.
 *
 * <p>The first is that <strong>every</strong> role answers. An enforcer holds no last-address file
 * and has nothing to contribute, and if it stayed quiet the bot would spend its whole request
 * timeout learning that. It answers {@code supported: false}, which is a different fact from an
 * empty list and has to stay different: "I have no alt data" read as "this player has no alts" is a
 * reassurance the server never gave. {@code known: false} is the third of those facts, for a server
 * that does hold the data and has simply never seen the player.
 *
 * <p>The second is that no address ever appears in the reply. The assertions below are written
 * against the serialised JSON and against the complete key list rather than against named keys, so
 * a field added later cannot smuggle one out under a name this test never thought to check.
 *
 * <p>The third is that the in-game {@code /dupeip} gives the same answer. It is pinned here, next
 * to the tunnel cases rather than off with the other command tests, because the property worth
 * protecting is not either answer on its own: it is that the two agree. They are one method apart
 * ({@link LastIpStore#altsOf}) and that is the only reason they cannot drift, so a test watching
 * one of them would not notice the day somebody gave the other its own matching.
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
            assertTrue(reply.bool("supported", false), "a gatekeeper holds a store");
            assertTrue(reply.bool("known", false), "and has seen this player");
            assertFalse(reply.bool("truncated", true), "three rows is not fifty");
            List<Payload> alts = reply.children("alts");
            assertEquals(2, alts.size(), reply.toJson());
            // Newest first. It is the order that makes a truncated list the useful half.
            assertEquals(HEROBRINE, alts.get(0).string("uuid", ""));
            assertEquals("Herobrine", alts.get(0).string("name", ""));
            assertEquals(3_000L, alts.get(0).longValue("lastSeenAt", 0L));
            assertEquals(ALEX, alts.get(1).string("uuid", ""));
            assertEquals("Alex", alts.get(1).string("name", ""));
            assertEquals(2_000L, alts.get(1).longValue("lastSeenAt", 0L));

            // The wire contract, spelled out. The bot correlates on the envelope id and reads these
            // exact keys; pinning the serialised form means a reordering or a renamed field is a
            // failing test here rather than an empty alt list on a dashboard nobody is watching.
            assertEquals("{\"uuid\":\"" + STEVE + "\",\"supported\":true,\"known\":true,\"alts\":["
                            + "{\"uuid\":\"" + HEROBRINE + "\",\"name\":\"Herobrine\",\"lastSeenAt\":3000},"
                            + "{\"uuid\":\"" + ALEX + "\",\"name\":\"Alex\",\"lastSeenAt\":2000}"
                            + "],\"truncated\":false}",
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

            assertEquals(1, uuidsOf(ask(harness, "req-3", STEVE)).size(),
                    "and the tunnel says the same thing");
        }
    }

    @Test
    @DisplayName("an enforcer replies supported:false rather than an empty list or silence")
    void enforcerReportsItHoldsNoStore() {
        try (PunishmentsHarness harness = new PunishmentsHarness(dataDir.resolve("enf"), ServerRole.ENFORCER)
                .enableReplace()) {
            assertEquals(1, harness.tunnel.subscriberCount(HeimdallPunishmentsModule.DUPEIP_QUERY),
                    "every role subscribes; only the answer differs");

            Payload reply = ask(harness, "req-4", STEVE);

            assertEquals(STEVE, reply.string("uuid", ""));
            assertFalse(reply.bool("supported", true),
                    "an enforcer never opens a last-address file, and says so rather than "
                            + "letting an empty list be read as 'this player has no alts'");
            assertFalse(reply.bool("known", true),
                    "a server with no store knows nothing, so known can never outrank supported");
            assertTrue(reply.hasArray("alts"), "alts is present and an array: " + reply.toJson());
            assertEquals(0, reply.children("alts").size(), reply.toJson());
            assertFalse(reply.bool("truncated", true), reply.toJson());
        }
    }

    @Test
    @DisplayName("a uuid the store has never seen is supported:true, known:false")
    void unknownUuidRepliesEmptyButSupported() {
        try (PunishmentsHarness harness = gatekeeper()) {
            harness.module.lastIpsForTest().record(ALEX, "Alex", SHARED_IP, 2_000L);

            Payload reply = ask(harness, "req-5", HEROBRINE);

            assertEquals(HEROBRINE, reply.string("uuid", ""));
            assertTrue(reply.bool("supported", false),
                    "the server did look, so it can answer about players it does know");
            assertFalse(reply.bool("known", true),
                    "but it has never seen this one, which is not the same as finding no alts");
            assertEquals(0, reply.children("alts").size(), reply.toJson());
        }
    }

    @Test
    @DisplayName("an empty or malformed uuid is known:false, not a silent or misleading answer")
    void aUselessUuidIsAnsweredHonestly() {
        try (PunishmentsHarness harness = gatekeeper()) {
            harness.module.lastIpsForTest().record(STEVE, "Steve", SHARED_IP, 1_000L);
            harness.module.lastIpsForTest().record(ALEX, "Alex", SHARED_IP, 2_000L);

            for (String useless : new String[] {"", "   ", "not-a-uuid"}) {
                Payload reply = ask(harness, "req-bad-" + useless.trim(), useless);

                assertTrue(reply.bool("supported", false),
                        "the store is open; the request is what was no good: " + reply.toJson());
                assertFalse(reply.bool("known", true),
                        "nothing was found, and saying so beats an empty list that reads as a "
                                + "clean bill of health: " + reply.toJson());
                assertEquals(0, reply.children("alts").size(), reply.toJson());
            }
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

            Payload reply = ask(harness, "req-6", STEVE);

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
            assertEquals("[uuid, supported, known, alts, truncated]", reply.keys().toString(), json);
        }
    }

    @Test
    @DisplayName("a name the store has never learned is omitted rather than sent empty")
    void unknownNameIsOmitted() {
        try (PunishmentsHarness harness = gatekeeper()) {
            LastIpStore ips = harness.module.lastIpsForTest();
            ips.record(STEVE, "Steve", SHARED_IP, 1_000L);
            ips.record(ALEX, null, SHARED_IP, 2_000L);

            Payload reply = ask(harness, "req-7", STEVE);

            List<Payload> alts = reply.children("alts");
            assertEquals(1, alts.size(), reply.toJson());
            assertEquals(ALEX, alts.get(0).string("uuid", ""));
            assertFalse(alts.get(0).has("name"), "an unknown name is absent, not \"\": " + reply.toJson());
            assertEquals(2_000L, alts.get(0).longValue("lastSeenAt", 0L));
        }
    }

    @Test
    @DisplayName("more alts than the limit are cut newest-first and reported as truncated")
    void tooManyAltsAreCutAndSaidSo() {
        try (PunishmentsHarness harness = gatekeeper()) {
            LastIpStore ips = harness.module.lastIpsForTest();
            ips.record(STEVE, "Steve", SHARED_IP, 0L);
            // Nothing prunes this file, and on a gatekeeper with no address forwarding every row
            // can share the proxy's address, so this is not a contrived shape.
            for (int i = 1; i <= HeimdallPunishmentsModule.DUPEIP_ALT_LIMIT + 1; i++) {
                ips.record(alt(i), "Player" + i, SHARED_IP, i);
            }

            Payload reply = ask(harness, "req-8", STEVE);

            List<Payload> alts = reply.children("alts");
            assertEquals(HeimdallPunishmentsModule.DUPEIP_ALT_LIMIT, alts.size(),
                    "the whole player history is not a tunnel frame");
            assertTrue(reply.bool("truncated", false),
                    "a cut list that does not say it was cut reads as the complete answer");
            assertEquals(alt(51), alts.get(0).string("uuid", ""), "newest first");
            assertEquals(51L, alts.get(0).longValue("lastSeenAt", 0L));
            assertEquals(alt(2), alts.get(alts.size() - 1).string("uuid", ""));
            assertFalse(uuidsOf(reply).contains(alt(1)),
                    "the oldest sighting is the one dropped, not an arbitrary row");
        }
    }

    @Test
    @DisplayName("a request in flight across a disable answers supported:false, not an exception")
    void aDisableUnderneathAnInFlightRequestIsSurvivable() {
        try (PunishmentsHarness harness = gatekeeper()) {
            harness.module.lastIpsForTest().record(STEVE, "Steve", SHARED_IP, 1_000L);
            harness.module.lastIpsForTest().record(ALEX, "Alex", SHARED_IP, 2_000L);
            // The handler as the subscription holds it: bus and logger captured at subscribe time.
            TunnelMessageHandler handler =
                    harness.module.dupeipHandler(harness.tunnel, harness.logger);

            harness.disableModule();
            harness.tunnel.clearSent();
            handler.onMessage(Envelope.of("req-9", HeimdallPunishmentsModule.DUPEIP_QUERY,
                    Payload.builder().put("uuid", STEVE).build()));

            // Reading the bus off the context field instead would be a NullPointerException here,
            // because disable() nulls it. The bot would then wait out its timeout for a reply the
            // plugin had already decided to send.
            List<RecordingTunnelBus.Sent> replies =
                    harness.tunnel.sent(HeimdallPunishmentsModule.DUPEIP_RESULT);
            assertEquals(1, replies.size(), harness.tunnel.sent().toString());
            assertEquals("req-9", replies.get(0).requestId());
            assertFalse(replies.get(0).payload().bool("supported", true),
                    "the store is closed, so the honest answer is that there is no alt data");
            assertFalse(replies.get(0).payload().bool("known", true));
            assertEquals(0, replies.get(0).payload().children("alts").size());
        }
    }

    @Test
    @DisplayName("a tunnel that throws on the way out does not throw out of the handler")
    void aFailedSendIsNotAnException() {
        try (PunishmentsHarness harness = gatekeeper()) {
            harness.module.lastIpsForTest().record(STEVE, "Steve", SHARED_IP, 1_000L);
            ThrowingTunnel broken = new ThrowingTunnel();

            harness.module.dupeipHandler(broken, harness.logger).onMessage(
                    Envelope.of("req-10", HeimdallPunishmentsModule.DUPEIP_QUERY,
                            Payload.builder().put("uuid", STEVE).build()));

            assertTrue(broken.attempted, "the reply was attempted");
            // The link died between the frame arriving and the answer going out. The bot times out,
            // which is the honest outcome of a dead socket, but the handler returns normally.
        }
    }

    @Test
    @DisplayName("the subscription is gone once the module is disabled")
    void disableUnsubscribes() {
        try (PunishmentsHarness harness = gatekeeper()) {
            assertEquals(1, harness.tunnel.subscriberCount(HeimdallPunishmentsModule.DUPEIP_QUERY));

            harness.disableModule();
            harness.tunnel.clearSent();

            assertEquals(0, harness.tunnel.subscriberCount(HeimdallPunishmentsModule.DUPEIP_QUERY),
                    "the subscription is made through ModuleContext, so a toggle has to unwind it");
            assertEquals(0, harness.tunnel.push(Envelope.of("req-11",
                            HeimdallPunishmentsModule.DUPEIP_QUERY,
                            Payload.builder().put("uuid", STEVE).build())),
                    "a disabled module answers nothing, and the bot times out as it would against "
                            + "a server that never had the module at all");
            assertEquals(0, harness.tunnel.sent().size());
        }
    }

    @Test
    @DisplayName("the handler is not subscribed on the socket's reading thread or the tick loop")
    void subscribesOnTheDefaultExecutor() {
        try (PunishmentsHarness harness = gatekeeper()) {
            // null means "the default", which is heimdall-io. Naming an executor here would be the
            // bug: the store scan would either serialise behind the tick loop or, on the socket
            // thread, stop the tunnel reading and get this server reaped as unresponsive.
            assertNull(harness.tunnel.subscribedExecutor(HeimdallPunishmentsModule.DUPEIP_QUERY));
        }
    }

    private PunishmentsHarness gatekeeper() {
        return new PunishmentsHarness(dataDir.resolve("gk"), ServerRole.GATEKEEPER).enableReplace();
    }

    /** A distinct uuid per index, so a bulk-record loop reads as a crowd rather than one player. */
    private static String alt(int index) {
        return String.format("%08d-0000-0000-0000-000000000000", index);
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

    /** A bus whose socket died between the request arriving and the answer going out. */
    private static final class ThrowingTunnel implements TunnelBus {

        boolean attempted;

        @Override
        public void send(String type, Payload payload) {
            throw new IllegalStateException("not connected");
        }

        @Override
        public void reply(String requestId, String type, Payload payload) {
            attempted = true;
            throw new IllegalStateException("the socket closed");
        }

        @Override
        public CompletableFuture<Payload> sendAndWait(String type, Payload payload) {
            throw new IllegalStateException("not connected");
        }

        @Override
        public CompletableFuture<Payload> sendAndWait(String type, Payload payload, long timeoutMs) {
            throw new IllegalStateException("not connected");
        }

        @Override
        public Registration subscribe(String type, TunnelMessageHandler handler) {
            return subscribe(type, handler, null);
        }

        @Override
        public Registration subscribe(String type, TunnelMessageHandler handler, Executor executor) {
            return Registration.once(new Runnable() {
                @Override
                public void run() {
                    // Nothing to unwind.
                }
            });
        }

        @Override
        public Registration onModeChange(com.heimdall.core.tunnel.ProtocolModeListener listener) {
            return Registration.NONE;
        }

        @Override
        public ProtocolMode mode() {
            return ProtocolMode.UNKNOWN;
        }

        @Override
        public boolean isConnected() {
            return false;
        }
    }
}
