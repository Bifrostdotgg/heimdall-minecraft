package com.heimdall.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.platform.Vanish;
import com.heimdall.core.platform.VanishListener;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.testing.FakePlayer;
import com.heimdall.core.tunnel.HealthSnapshotSource;
import com.heimdall.core.util.Registration;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The health half of vanish reporting (D92); the roster half is in {@code RemoteRequestWiringTest}. */
class VanishReportingTest {

    @TempDir
    Path dataDir;

    /** A vanish integration that hides exactly {@code hidden}. */
    static Vanish hiding(final UUID hidden) {
        return integration(new Function<PlayerHandle, Boolean>() {
            @Override
            public Boolean apply(PlayerHandle player) {
                return hidden.equals(player.uuid());
            }
        });
    }

    /** One that breaks its own contract and throws. */
    static Vanish throwing() {
        return integration(new Function<PlayerHandle, Boolean>() {
            @Override
            public Boolean apply(PlayerHandle player) {
                throw new IllegalStateException("PremiumVanish exploded");
            }
        });
    }

    private static Vanish integration(final Function<PlayerHandle, Boolean> answer) {
        return new Vanish() {
            @Override
            public long joinWaitMillis(PlayerHandle player, long elapsedMs) {
                return 0L;
            }

            @Override
            public boolean isVanished(PlayerHandle player) {
                return answer.apply(player).booleanValue();
            }

            @Override
            public Registration onToggle(VanishListener listener) {
                return Registration.NONE;
            }
        };
    }

    private FakePlatform platform() {
        return new FakePlatform(ServerRole.STANDALONE, dataDir);
    }

    private static Payload snapshot(int online) {
        return Payload.builder().put("onlinePlayers", online).put("maxPlayers", 20)
                .put("motdClean", "").build();
    }

    @Test
    @DisplayName("both counts come from one roster read, and a hidden player is counted")
    void bothCountsComeFromOneRead() {
        FakePlatform platform = platform();
        FakePlayer staff = platform.join(FakePlayer.named("Staff"));
        platform.join(FakePlayer.named("Steve"));
        platform.withVanish(hiding(staff.uuid()));

        // The platform's own count is stale on purpose: it must be replaced, not kept beside a
        // vanish count taken from a different read.
        Payload sent = VanishReporting.withVanishCount(snapshot(7), platform);

        assertEquals(2, sent.intValue("onlinePlayers", -1));
        assertEquals(1, sent.intValue("vanishedPlayers", -1));
        assertEquals(20, sent.intValue("maxPlayers", -1), "the rest of the snapshot is untouched");
    }

    @Test
    @DisplayName("nobody hidden is sent as zero, not omitted")
    void nobodyHiddenIsZero() {
        FakePlatform platform = platform();
        platform.join(FakePlayer.named("Steve"));

        Payload sent = VanishReporting.withVanishCount(snapshot(1), platform);

        assertEquals(1, sent.intValue("onlinePlayers", -1));
        assertTrue(sent.has("vanishedPlayers"));
        assertEquals(0, sent.intValue("vanishedPlayers", -1));
    }

    @Test
    @DisplayName("a throwing vanish integration counts the player as hidden")
    void aThrowingIntegrationCountsAsHidden() {
        FakePlatform platform = platform();
        platform.join(FakePlayer.named("Staff"));
        platform.withVanish(throwing());

        Payload sent = VanishReporting.withVanishCount(snapshot(1), platform);

        assertEquals(1, sent.intValue("vanishedPlayers", -1));
    }

    @Test
    @DisplayName("a roster that cannot be read drops both counts rather than send the total alone")
    void anUnreadableRosterDropsBothCounts() {
        final FakePlatform fake = platform();
        PlatformFacade platform = (PlatformFacade) Proxy.newProxyInstance(
                PlatformFacade.class.getClassLoader(), new Class<?>[] {PlatformFacade.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args)
                            throws Throwable {
                        if ("players".equals(method.getName())) {
                            throw new IllegalStateException("the server is stopping");
                        }
                        return method.invoke(fake, args);
                    }
                });

        Payload sent = VanishReporting.withVanishCount(snapshot(3), platform);

        assertFalse(sent.has("onlinePlayers"),
                "the total on its own would be published with hidden staff still in it");
        assertFalse(sent.has("vanishedPlayers"));
        assertEquals(20, sent.intValue("maxPlayers", -1), "the heartbeat still carries the rest");
    }

    @Test
    @DisplayName("a snapshot with no player count is left alone")
    void noCountIsLeftAlone() {
        Payload partial = Payload.builder().put("motdClean", "").build();

        assertSame(partial, VanishReporting.withVanishCount(partial, platform()));
        assertNull(VanishReporting.withVanishCount(null, platform()));
    }

    @Test
    @DisplayName("the wrapper passes through, and no source stays no source")
    void theWrapperPassesThrough() {
        FakePlatform platform = platform();
        FakePlayer staff = platform.join(FakePlayer.named("Staff"));
        platform.withVanish(hiding(staff.uuid()));

        assertNull(VanishReporting.counting(null, platform));
        HealthSnapshotSource wrapped = VanishReporting.counting(new HealthSnapshotSource() {
            @Override
            public Payload snapshot() {
                return VanishReportingTest.snapshot(1);
            }
        }, platform);
        assertEquals(1, wrapped.snapshot().intValue("vanishedPlayers", -1));
    }
}
