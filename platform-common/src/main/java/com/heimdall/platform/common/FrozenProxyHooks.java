package com.heimdall.platform.common;

import com.heimdall.module.punishments.HeimdallPunishmentsModule;
import com.heimdall.module.punishments.ServerProbe;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The proxy's half of a freeze: refusing the commands the proxy runs itself, and telling a kick
 * from a logout.
 *
 * <p>Here rather than in each proxy platform because this package is the one both proxies
 * already depend on that can see the punishments module. See
 * {@link HeimdallPunishmentsModule#refuseFrozenProxyCommand} for what is refused and why server
 * switches are not.
 */
public final class FrozenProxyHooks {

    private FrozenProxyHooks() {
    }

    /**
     * Whether a proxy-owned command from this player is refused because they are frozen. The
     * player has been told when it is. False when the punishments module is not running, so a
     * proxy without it behaves exactly as before.
     */
    public static boolean refuse(UUID player) {
        HeimdallPunishmentsModule module = HeimdallPunishmentsModule.INSTANCE;
        return module != null && player != null && module.refuseFrozenProxyCommand(player);
    }

    /**
     * A backend kick disconnected this player. If they are frozen, staff hear "was kicked", and a
     * logout ban held for it fires only if that backend still answers {@code ping}, which pings it
     * and answers once with whether it replied. See
     * {@link HeimdallPunishmentsModule#noteKicked(UUID, ServerProbe)}.
     */
    public static void kicked(UUID player, final Consumer<Consumer<Boolean>> ping) {
        HeimdallPunishmentsModule module = HeimdallPunishmentsModule.INSTANCE;
        if (module == null || player == null) return;
        module.noteKicked(player, ping == null ? null : new ServerProbe() {
            @Override
            public void reachable(Consumer<Boolean> answer) {
                ping.accept(answer);
            }
        });
    }
}
