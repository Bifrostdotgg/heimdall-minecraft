package com.heimdall.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.http.BedrockIdentityProvider;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.pipeline.Verdict;
import com.heimdall.core.text.Msg;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The guard in front of the login pipeline, and why it is not {@code ignoreCancelled}.
 *
 * <p>The event is constructed directly rather than mocked: {@code AsyncPlayerPreLoginEvent} is a
 * plain object with a result field on every supported version, and its real
 * {@code disallow}/{@code getLoginResult} pair is the thing under test.
 */
class BukkitLoginListenerTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final LoginPipeline pipeline = new LoginPipeline(logger);

    private static final UUID STEVE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private BukkitLoginListener listener() {
        return new BukkitLoginListener(logger, pipeline, BedrockIdentityProvider.NONE);
    }

    private static AsyncPlayerPreLoginEvent event() throws UnknownHostException {
        return new AsyncPlayerPreLoginEvent("Steve", InetAddress.getByName("203.0.113.7"), STEVE);
    }

    @Test
    @DisplayName("a connection another plugin already refused is left exactly as it was")
    void anAlreadyRefusedLoginIsNotTouched() throws Exception {
        final AtomicInteger dispatches = new AtomicInteger();
        pipeline.register(attempt -> {
            dispatches.incrementAndGet();
            return Verdict.deny(Msg.legacy("&cYou are not whitelisted."));
        }, 10, "test");

        AsyncPlayerPreLoginEvent event = event();
        // What a ban plugin running earlier leaves behind. ignoreCancelled cannot express this:
        // AsyncPlayerPreLoginEvent is not Cancellable, so Bukkit never reads that flag for it.
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                "You are banned until 2026-08-01. Appeal at example.invalid");

        listener().onPreLogin(event);

        assertEquals(0, dispatches.get(),
                "there is nothing left to decide, and asking the bot about a connection that is "
                        + "already refused is a wasted round trip on the login path");
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, event.getLoginResult());
        assertTrue(event.getKickMessage().contains("Appeal at"),
                "this is the actual damage: disallow() overwrites unconditionally, so the ban's "
                        + "expiry and appeal text became 'you are not whitelisted' and the staff "
                        + "member asked about it had no idea a ban plugin was involved");
    }

    @Test
    @DisplayName("an allowed connection is still checked, and a denial still lands")
    void anAllowedLoginIsStillDecided() throws Exception {
        pipeline.register(attempt -> Verdict.deny(Msg.legacy("&cYou are not whitelisted.")), 10, "test");

        AsyncPlayerPreLoginEvent event = event();

        listener().onPreLogin(event);

        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, event.getLoginResult());
        assertTrue(event.getKickMessage().contains("not whitelisted"), event.getKickMessage());
    }

    @Test
    @DisplayName("an allowed connection nobody denies stays allowed")
    void anAllowedLoginPassesThrough() throws Exception {
        pipeline.register(attempt -> Verdict.abstain(), 10, "test");

        AsyncPlayerPreLoginEvent event = event();

        listener().onPreLogin(event);

        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, event.getLoginResult());
    }
}
