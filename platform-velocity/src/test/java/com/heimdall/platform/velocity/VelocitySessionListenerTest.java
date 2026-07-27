package com.heimdall.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.session.PlayerSessionEvents;
import com.heimdall.core.session.PlayerSessionListener;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which disconnects count as the end of a session.
 *
 * <p>The failure this guards is quiet and backwards: the whitelist module's quit listener slides a
 * player's mirror expiry <em>forward</em> by the leave window, so reporting a refused login as a
 * quit means being denied extends the cached decision that admits them — and a player bouncing off
 * the login gate does it once per attempt.
 */
class VelocitySessionListenerTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    private final PlayerSessionEvents sessions =
            new PlayerSessionEvents(logger, Runnable::run);

    private final AtomicInteger quits = new AtomicInteger();

    private VelocitySessionListener listener() {
        sessions.onQuit(new PlayerSessionListener() {
            @Override
            public void onPlayerSession(PlayerHandle player, long timestampMs) {
                quits.incrementAndGet();
            }
        });
        return new VelocitySessionListener(logger, sessions, new VelocityText(logger));
    }

    private static DisconnectEvent disconnect(DisconnectEvent.LoginStatus status) {
        Player player = mock(Player.class);
        when(player.getUsername()).thenReturn("Steve");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return new DisconnectEvent(player, status);
    }

    @Test
    @DisplayName("a successful login's disconnect is a quit")
    void successfulLoginQuits() {
        listener().onDisconnect(disconnect(DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));

        assertEquals(1, quits.get());
    }

    @Test
    @DisplayName("a player who reached the proxy but no backend is still a quit")
    void preServerJoinQuits() {
        // PostLoginEvent has already fired by this point, so its join was reported. Skipping the
        // matching quit would leave a session nothing ever closes.
        listener().onDisconnect(disconnect(DisconnectEvent.LoginStatus.PRE_SERVER_JOIN));

        assertEquals(1, quits.get());
    }

    @Test
    @DisplayName("a login the proxy REFUSED is not a quit")
    void cancelledByProxyIsNotAQuit() {
        // This is the whitelist gate turning somebody away. Counting it would slide the mirror
        // expiry that lets them in FORWARD, once per rejected attempt.
        listener().onDisconnect(disconnect(DisconnectEvent.LoginStatus.CANCELLED_BY_PROXY));

        assertEquals(0, quits.get());
    }

    @Test
    @DisplayName("a login the user abandoned is not a quit either")
    void cancelledByUserIsNotAQuit() {
        listener().onDisconnect(
                disconnect(DisconnectEvent.LoginStatus.CANCELLED_BY_USER_BEFORE_COMPLETE));

        assertEquals(0, quits.get());
    }

    @Test
    @DisplayName("a conflicting login is not a quit")
    void conflictingLoginIsNotAQuit() {
        listener().onDisconnect(disconnect(DisconnectEvent.LoginStatus.CONFLICTING_LOGIN));

        assertEquals(0, quits.get());
    }

    // There is deliberately no test for a null status: DisconnectEvent's constructor rejects one
    // (checkNotNull, verified against velocity-api 3.4.0), so the guard in the listener is
    // unreachable through the current API and exists only against a future one. Constructing the
    // case would need a mock, and DisconnectEvent is final — which is a fair price for a branch
    // whose behaviour is stated in the listener's own javadoc.
    //
    // The allow-list is read by NAME rather than by enum constant for the related reason: a status
    // added in a later Velocity lands on the "not a quit" side rather than failing to compile or,
    // worse, silently counting as a join.
}
