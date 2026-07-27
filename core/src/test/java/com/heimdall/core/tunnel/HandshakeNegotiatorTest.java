package com.heimdall.core.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.AbstractHeimdallLogger;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The handshake's two races, driven by hand.
 *
 * <p>Both are about a decision that is made <strong>once per connection</strong> and then lives for
 * the whole life of the socket. Getting either wrong does not produce an error — it produces a
 * server quietly running on cached configuration, or one wedged mid-handshake, with nothing in any
 * log to say which.
 */
class HandshakeNegotiatorTest {

    private final CapturingScheduler scheduler = new CapturingScheduler();
    private final List<Envelope> sent = new CopyOnWriteArrayList<Envelope>();

    private static final TunnelSettings SETTINGS = TunnelSettings.builder()
            .endpoint("http://127.0.0.1:1")
            .guildId("123456789012345678")
            .serverId("survival")
            .apiKey("test-secret-key")
            .negotiationTimeoutMs(10_000L)
            .build();

    private HandshakeNegotiator negotiator(com.heimdall.core.log.HeimdallLogger logger) {
        return negotiator(logger, Collections.singleton(Capabilities.WHITELIST));
    }

    private HandshakeNegotiator negotiator(
            com.heimdall.core.log.HeimdallLogger logger, Set<String> declared) {
        return new HandshakeNegotiator(
                logger,
                scheduler,
                sent::add,
                () -> ServerIdentity.builder().serverName("Survival").platform("bukkit").build(),
                () -> declared,
                document -> {
                });
    }

    private static Payload accepted() {
        return Payload.builder().put("accepted", true).put("configVersion", 7).build();
    }

    /** What the real bot sends: `accepted` is the LIST of capabilities it will honour. */
    private static Payload acceptedList(int configVersion, String... capabilities) {
        return Payload.builder()
                .put("protocolVersion", 3)
                .putStrings("accepted", Arrays.asList(capabilities))
                .put("configVersion", configVersion)
                .build();
    }

    private Envelope identifyFrame() {
        for (Envelope frame : sent) {
            if ("identify".equals(frame.type())) {
                return frame;
            }
        }
        return null;
    }

    // ── B1: the deadline must not demote a negotiated connection ─────────────

    @Test
    @DisplayName("an ack landing while the deadline is mid-decision does not demote the connection")
    void theDeadlineCannotOverwriteAnAckItRacedWith() {
        // Forcing the interleave needs a point INSIDE the deadline's decision to act from, and the
        // logger is that point: the deadline logs its "this bot speaks v2" line as part of
        // deciding. A logger that delivers the ack when it sees that line puts the ack exactly
        // where a real race would put it — between reading the mode and acting on it.
        //
        // The fix is that there is no longer a gap there: the check and the transition happen
        // together under the lock and the log comes afterwards, so an ack delivered from the log
        // arrives after the decision rather than inside it. With the check outside the lock, this
        // ack is applied first and then overwritten, and the connection spends its life in v2
        // compatibility having been told, correctly, that the bot speaks v3.
        final AtomicBoolean ackDelivered = new AtomicBoolean();
        final HandshakeNegotiator[] holder = new HandshakeNegotiator[1];
        AbstractHeimdallLogger interleaving = new AbstractHeimdallLogger(true) {
            @Override
            protected void write(LogLevel level, String message, Throwable throwable) {
                if (message.contains("no identify_ack") && ackDelivered.compareAndSet(false, true)) {
                    holder[0].handle(Envelope.of(
                            identifyFrame().id(), "identify_ack", accepted()));
                }
            }
        };

        HandshakeNegotiator negotiator = negotiator(interleaving);
        holder[0] = negotiator;
        negotiator.onOpen(SETTINGS);
        assertNotNull(identifyFrame(), "identify is sent before the deadline is armed");

        scheduler.runLatest();

        assertTrue(ackDelivered.get(), "the interleave did not happen; the test proves nothing");
        assertEquals(ProtocolMode.V3, negotiator.mode(),
                "the bot demonstrably speaks v3 — a deadline that fired at the same moment must not "
                        + "be able to leave the connection on cached config for its whole life");
        assertEquals(7, negotiator.configVersion());
    }

    @Test
    @DisplayName("a deadline that fires after an ack has already been applied changes nothing")
    void aLateDeadlineIsInert() {
        RecordingLogger logger = new RecordingLogger(true);
        HandshakeNegotiator negotiator = negotiator(logger);
        negotiator.onOpen(SETTINGS);

        negotiator.handle(Envelope.of(identifyFrame().id(), "identify_ack", accepted()));
        assertEquals(ProtocolMode.V3, negotiator.mode());

        // A real ScheduledFuture that has already started running cannot be cancelled, so the
        // deadline body genuinely can run after the ack.
        scheduler.runLatest();

        assertEquals(ProtocolMode.V3, negotiator.mode());
        assertFalse(logger.logged(LogLevel.INFO, "no identify_ack"),
                "and it must not claim in the log to have concluded something it did not");
    }

    // ── The ack's id is not a correlation ────────────────────────────────────

    @Test
    @DisplayName("an ack is accepted whatever id it carries — the bot does not echo ours")
    void anAckWithAnUnrelatedIdIsStillOurs() {
        RecordingLogger logger = new RecordingLogger(true);
        HandshakeNegotiator negotiator = negotiator(logger);
        negotiator.onOpen(SETTINGS);

        // Exactly what the real bot sends: a fresh id, unrelated to the identify's.
        negotiator.handle(Envelope.of("a-fresh-id-from-the-bot", "identify_ack", accepted()));

        assertEquals(ProtocolMode.V3, negotiator.mode(),
                "requiring the ack to echo the identify's id discarded every ack the real bot "
                        + "sends, so the plugin timed out into v2-compat on every connection while "
                        + "the bot believed it had negotiated v3");
        assertEquals(7, negotiator.configVersion());
        assertFalse(scheduler.latestIsArmed());
    }

    @Test
    @DisplayName("an ack arriving outside a handshake is ignored and disarms nothing")
    void anAckOutsideAHandshakeIsIgnored() {
        RecordingLogger logger = new RecordingLogger(true);
        HandshakeNegotiator negotiator = negotiator(logger);
        negotiator.onOpen(SETTINGS);
        negotiator.onClosed();

        negotiator.handle(Envelope.of("late", "identify_ack", accepted()));

        assertEquals(ProtocolMode.UNKNOWN, negotiator.mode(),
                "an ack for a connection that has already been torn down must not decide anything");
        assertEquals(-1, negotiator.configVersion());
    }

    @Test
    @DisplayName("a real ack disarms the deadline")
    void aRealAckDisarmsTheDeadline() {
        RecordingLogger logger = new RecordingLogger(true);
        HandshakeNegotiator negotiator = negotiator(logger);
        negotiator.onOpen(SETTINGS);

        negotiator.handle(Envelope.of(identifyFrame().id(), "identify_ack", accepted()));

        assertFalse(scheduler.latestIsArmed());
    }

    // -- `accepted` is a list (departure D51) --------------------------------

    @Test
    @DisplayName("the accepted list is read, in the client's own spelling")
    void acceptedIsReadAsAList() {
        RecordingLogger logger = new RecordingLogger(true);
        HandshakeNegotiator negotiator = negotiator(logger);
        negotiator.onOpen(SETTINGS);

        negotiator.handle(Envelope.of("fresh", "identify_ack",
                acceptedList(11, Capabilities.WHITELIST, Capabilities.CONSOLE)));

        assertEquals(ProtocolMode.V3, negotiator.mode());
        assertEquals(
                Arrays.asList(Capabilities.WHITELIST, Capabilities.CONSOLE),
                negotiator.acceptedCapabilities(),
                "returning an empty list here passed every other assertion in this file, which is "
                        + "how the list handling went uncovered");
        assertEquals(11, negotiator.configVersion());
    }

    @Test
    @DisplayName("a declared capability missing from the list is named, once")
    void unacceptedCapabilitiesAreReported() {
        RecordingLogger logger = new RecordingLogger(true);
        HandshakeNegotiator negotiator = negotiator(logger,
                new LinkedHashSet<String>(
                        Arrays.asList(Capabilities.WHITELIST, Capabilities.ROLE_SYNC)));
        negotiator.onOpen(SETTINGS);

        negotiator.handle(Envelope.of("fresh", "identify_ack",
                acceptedList(1, Capabilities.WHITELIST)));

        assertTrue(logger.logged(LogLevel.WARN, Capabilities.ROLE_SYNC),
                "a module that is enabled and will never be driven is otherwise perfectly silent: "
                        + logger.records());
    }

    @Test
    @DisplayName("an empty accepted list is a successful handshake, and names nothing")
    void anEmptyAcceptedListIsStillV3() {
        RecordingLogger logger = new RecordingLogger(true);
        HandshakeNegotiator negotiator = negotiator(logger);
        negotiator.onOpen(SETTINGS);

        negotiator.handle(Envelope.of("fresh", "identify_ack", acceptedList(3)));

        assertEquals(ProtocolMode.V3, negotiator.mode(),
                "there is no refusal frame in this protocol; an empty list means the bot "
                        + "recognised none of what this build declared");
        assertTrue(negotiator.acceptedCapabilities().isEmpty());
        // Deliberate: a bot that acked with the older boolean shape also lands on an empty list,
        // and inventing a warning out of its silence would name every capability this build has.
        assertFalse(logger.logged(LogLevel.WARN, Capabilities.WHITELIST));
    }

    @Test
    @DisplayName("an explicit accepted:false still demotes, for a bot answering the older shape")
    void anExplicitBooleanRefusalDemotes() {
        RecordingLogger logger = new RecordingLogger(true);
        HandshakeNegotiator negotiator = negotiator(logger);
        negotiator.onOpen(SETTINGS);

        negotiator.handle(Envelope.of("fresh", "identify_ack", Payload.builder()
                .put("accepted", false)
                .put("reason", "unsupported plugin version")
                .build()));

        assertEquals(ProtocolMode.V2_COMPAT, negotiator.mode());
        assertTrue(logger.logged(LogLevel.SEVERE, "unsupported plugin version"));
    }

    // -- A second ack on a live socket (departure D51) -----------------------

    @Test
    @DisplayName("a second identify_ack on the same socket is ignored, not reprocessed")
    void aRepeatAckIsInert() {
        RecordingLogger logger = new RecordingLogger(true);
        HandshakeNegotiator negotiator = negotiator(logger);
        negotiator.onOpen(SETTINGS);

        negotiator.handle(Envelope.of("first", "identify_ack",
                acceptedList(9, Capabilities.WHITELIST)));
        assertEquals(ProtocolMode.V3, negotiator.mode());

        // A re-sent or replayed ack. Reprocessing it would reset the accepted set and the config
        // version, and an `accepted: false` in it would demote a link that is demonstrably working.
        negotiator.handle(Envelope.of("second", "identify_ack", Payload.builder()
                .put("accepted", false)
                .put("reason", "should never be applied")
                .build()));

        assertEquals(ProtocolMode.V3, negotiator.mode(),
                "the handshake is decided once per connection");
        assertEquals(9, negotiator.configVersion());
        assertEquals(Collections.singletonList(Capabilities.WHITELIST),
                negotiator.acceptedCapabilities());
        assertFalse(logger.logged(LogLevel.SEVERE, "should never be applied"));
    }

    @Test
    @DisplayName("a fresh connection negotiates again after a close")
    void reopeningRenegotiates() {
        RecordingLogger logger = new RecordingLogger(true);
        HandshakeNegotiator negotiator = negotiator(logger);
        negotiator.onOpen(SETTINGS);
        negotiator.handle(Envelope.of("first", "identify_ack", acceptedList(9)));
        negotiator.onClosed();

        negotiator.onOpen(SETTINGS);
        negotiator.handle(Envelope.of("second", "identify_ack",
                acceptedList(12, Capabilities.WHITELIST)));

        assertEquals(ProtocolMode.V3, negotiator.mode());
        assertEquals(12, negotiator.configVersion(),
                "clearing the identify id on success must not stop the NEXT connection acking");
    }

    @Test
    @DisplayName("closing rearms nothing and forgets the negotiated mode")
    void closingResetsEverything() {
        RecordingLogger logger = new RecordingLogger(true);
        HandshakeNegotiator negotiator = negotiator(logger);
        negotiator.onOpen(SETTINGS);
        negotiator.handle(Envelope.of(identifyFrame().id(), "identify_ack", accepted()));

        negotiator.onClosed();

        assertEquals(ProtocolMode.UNKNOWN, negotiator.mode());
        assertEquals(-1, negotiator.configVersion());
        assertFalse(scheduler.latestIsArmed());
    }
}
