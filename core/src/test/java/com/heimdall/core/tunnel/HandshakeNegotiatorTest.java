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
import java.util.Collections;
import java.util.List;
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
        return new HandshakeNegotiator(
                logger,
                scheduler,
                sent::add,
                () -> ServerIdentity.builder().serverName("Survival").platform("bukkit").build(),
                () -> Collections.singleton(Capabilities.WHITELIST),
                document -> {
                });
    }

    private static Payload accepted() {
        return Payload.builder().put("accepted", true).put("configVersion", 7).build();
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
